package org.snapvm;

import java.lang.reflect.Array;

/**
 * The bytecode interpreter. Covers the Java 1.4 instruction set
 * (no invokedynamic; jsr/ret unsupported — preverified J2ME code doesn't use them).
 *
 * Wide values (long/double) occupy a value slot followed by {@link Frame#WIDE}.
 */
public final class Interpreter {
	private Interpreter() {
	}

	static long execute(Vm vm, VmThread t, long budget) {
		return execute(vm, t, budget, -1);
	}

	/** Run until budget out / blocked / dead / frames shrink to {@code depth}. */
	static long execute(Vm vm, VmThread t, long budget, int depth) {
		long used = 0;
		while (used < budget) {
			if (t.state != VmThread.RUNNABLE) {
				if (t.state == VmThread.TIMED_WAITING
						&& System.currentTimeMillis() >= t.wakeTime)
					t.state = VmThread.RUNNABLE;
				else
					break;
			}
			if (t.waitMonitor != null && !reacquire(t))
				break;
			Frame f = t.top();
			if (f == null) {
				t.terminate();
				break;
			}
			if (depth >= 0 && t.frames.size() <= depth)
				break;
			used++;
			try {
				step(vm, t, f);
			} catch (VmException e) {
				throwObject(vm, t, e.thrown);
			}
		}
		return used;
	}

	/** Re-acquire a monitor for a thread resuming from wait()/contention. */
	private static boolean reacquire(VmThread t) {
		Object mo = t.waitMonitor;
		Vm.Monitor m = t.vm.monitorOf(mo);
		synchronized (m) {
			if (m.owner == null || m.owner == t) {
				m.owner = t;
				m.count = Math.max(1, t.waitReleasedCount);
				t.waitReleasedCount = 0;
				t.waitMonitor = null;
				return true;
			}
			t.state = VmThread.BLOCKED;
			return false;
		}
	}

	// ---------- guest-level throw ----------

	static void throwObject(Vm vm, VmThread t, Object thrown) {
		String trace = trace(t); // capture before frames are popped
		boolean callerCallSite = false;
		while (!t.frames.isEmpty()) {
			Frame f = t.top();
			// when a callee frame was just popped, the throw site in this frame is
			// the invoke instruction, not f.pc (already advanced past it)
			int throwPc = callerCallSite && f.callPc >= 0 ? f.callPc : f.pc;
			ClassFile.Method m = f.method;
			boolean handled = false;
			if (m.code != null) {
				for (ClassFile.ExEntry e : m.code.exTable) {
					if (throwPc < e.startPc || throwPc >= e.endPc)
						continue;
					if (e.catchType == null) {
						jumpToHandler(f, e, thrown);
						handled = true;
						break;
					}
					VmClass catchCls;
					try {
						catchCls = vm.resolveClass(e.catchType);
					} catch (VmException ex) {
						continue;
					}
					if (catchCls.isInstance(thrown)) {
						jumpToHandler(f, e, thrown);
						handled = true;
						break;
					}
				}
			}
			if (handled)
				return;
			callerCallSite = f.fromCall;
			t.frames.remove(t.frames.size() - 1);
			finishClinitIfNeeded(f);
		}
		VmException ve = new VmException(thrown);
		ve.vmTrace = trace;
		t.lastError = ve;
		t.terminate();
	}

	private static void jumpToHandler(Frame f, ClassFile.ExEntry e, Object thrown) {
		f.pc = e.handlerPc;
		f.sp = 0;
		f.stack[f.sp++] = thrown;
	}

	static String trace(VmThread t) {
		StringBuilder sb = new StringBuilder();
		for (int i = t.frames.size() - 1; i >= 0; i--)
			sb.append("\n  at ").append(t.frames.get(i).describe());
		return sb.toString();
	}

	private static void finishClinitIfNeeded(Frame f) {
		if (f.method.name.equals("<clinit>"))
			f.owner.vm.finishInit(f.owner);
	}

	// ---------- invocation ----------

	// kind: 0 virtual/interface, 1 static, 2 special
	private static void invoke(Vm vm, VmThread t, Frame f, ClassFile.Ref r, int pc, int oplen,
			int kind) {
		VmClass owner = vm.resolveClass(r.owner);
		if (kind == 1) {
			if (owner.host) {
				Object[] args = popToArray(f, r.desc, false);
				Object ret = vm.bridge.call(t, r.owner, r.name, r.desc, args, 1);
				f.pc = pc + oplen;
				pushRet(f, r.desc, ret);
				return;
			}
			if (!vm.ensureInit(owner, t))
				return; // clinit frame pushed: retry instruction next step
			ClassFile.Method m = owner.findDeclared(r.name, r.desc);
			if (m == null)
				throw new VmException(new NoSuchMethodError(owner.name + "." + r.name + r.desc));
			if (m.code == null)
				throw new VmException(new AbstractMethodError(owner.name + "." + r.name));
			pushGuestFrame(t, f, declaring(owner, m), m, r.desc, pc, oplen, false);
			return;
		}

		Object recv = f.stack[f.sp - VmClass.argSlots(r.desc) - 1];
		if (kind == 2) {
			// invokespecial
			if (recv instanceof HostBridge.NewMarker) {
				HostBridge.NewMarker mk = (HostBridge.NewMarker) recv;
				Object[] args = popToArray(f, r.desc, false);
				f.sp--; // pop marker
				Object built = vm.bridge.call(t, mk.cls.name, "<init>", r.desc, args, 3);
				replaceMarker(t, mk, built);
				f.pc = pc + oplen;
				return;
			}
			if (recv == null)
				throw new VmException(new NullPointerException());
			if (owner.host) {
				Object[] args = popToArray(f, r.desc, true);
				Object ret = vm.bridge.call(t, r.owner, r.name, r.desc, args, 2);
				f.pc = pc + oplen;
				pushRet(f, r.desc, ret);
				return;
			}
			ClassFile.Method m = owner.findDeclared(r.name, r.desc);
			if (m == null || m.code == null)
				throw new VmException(new AbstractMethodError(owner.name + "." + r.name));
			pushGuestFrame(t, f, declaring(owner, m), m, r.desc, pc, oplen, true);
			return;
		}

		// virtual/interface
		if (recv == null)
			throw new VmException(new NullPointerException());
		if (!(recv instanceof VmObject)) {
			Object[] args = popToArray(f, r.desc, true);
			Object ret = vm.bridge.call(t, r.owner, r.name, r.desc, args, 0);
			f.pc = pc + oplen;
			pushRet(f, r.desc, ret);
			return;
		}
		VmClass rc = ((VmObject) recv).clazz;
		ClassFile.Method m = rc.findMethod(r.name, r.desc);
		if (m != null && m.code == null)
			throw new VmException(new AbstractMethodError(rc.name + "." + r.name));
		if (m == null) {
			// falls back to a host-root method (Object.*) not overridden
			Object[] args = popToArray(f, r.desc, true);
			Object ret = vm.bridge.call(t, "java/lang/Object", r.name, r.desc, args, 0);
			f.pc = pc + oplen;
			pushRet(f, r.desc, ret);
			return;
		}
		pushGuestFrame(t, f, declaring(rc, m), m, r.desc, pc, oplen, true);
	}

	private static VmClass declaring(VmClass start, ClassFile.Method m) {
		for (VmClass c = start; c != null && !c.host; c = c.superClass)
			if (c.declaredMethods.containsValue(m))
				return c;
		return start;
	}

	private static void pushRet(Frame f, String desc, Object ret) {
		String rt = VmClass.returnType(desc);
		if (!rt.equals("V")) {
			f.stack[f.sp++] = ret;
			if (VmClass.isWide(rt))
				f.stack[f.sp++] = Frame.WIDE;
		}
	}

	private static void pushGuestFrame(VmThread t, Frame caller, VmClass decl,
			ClassFile.Method m, String desc, int pc, int oplen, boolean hasThis) {
		Object[] args = popToArray(caller, desc, hasThis);
		caller.pc = pc + oplen;
		caller.callPc = pc;
		Frame nf = new Frame(decl, m);
		nf.fromCall = true;
		Vm.fillArgs(nf, desc, args, hasThis);
		t.frames.add(nf);
	}

	private static Object[] popToArray(Frame f, String desc, boolean hasThis) {
		int n = VmClass.argSlots(desc) + (hasThis ? 1 : 0);
		Object[] a = new Object[n];
		for (int i = n - 1; i >= 0; i--) {
			Object v = f.stack[--f.sp];
			if (v == Frame.WIDE)
				v = f.stack[--f.sp];
			a[i] = v;
		}
		return a;
	}

	private static void replaceMarker(VmThread t, HostBridge.NewMarker mk, Object built) {
		for (Frame f : t.frames) {
			for (int i = 0; i < f.sp; i++)
				if (f.stack[i] == mk)
					f.stack[i] = built;
			for (int i = 0; i < f.locals.length; i++)
				if (f.locals[i] == mk)
					f.locals[i] = built;
		}
	}

	// ---------- field access ----------

	private static void getPutStatic(Vm vm, VmThread t, Frame f, ClassFile.Ref r, int pc,
			boolean isPut) {
		VmClass owner = vm.resolveClass(r.owner);
		if (owner.host) {
			if (isPut) {
				Object v = f.stack[--f.sp];
				if (v == Frame.WIDE)
					v = f.stack[--f.sp];
				vm.bridge.putStaticField(owner, r, v);
			} else {
				f.stack[f.sp++] = vm.bridge.getStaticField(owner, r);
				if (VmClass.isWide(r.desc))
					f.stack[f.sp++] = Frame.WIDE;
			}
			f.pc = pc + 3;
			return;
		}
		if (!vm.ensureInit(owner, t))
			return; // clinit pushed; retry
		VmClass decl = owner.staticFieldOwner(r.name, r.desc);
		if (decl == null)
			throw new VmException(new NoSuchFieldError(r.owner + "." + r.name));
		int slot = decl.ownStaticSlot(r.name, r.desc);
		if (isPut) {
			Object v = f.stack[--f.sp];
			if (v == Frame.WIDE)
				v = f.stack[--f.sp];
			decl.statics[slot] = v;
		} else {
			f.stack[f.sp++] = decl.statics[slot];
			if (VmClass.isWide(r.desc))
				f.stack[f.sp++] = Frame.WIDE;
		}
		f.pc = pc + 3;
	}

	private static void getPutField(Vm vm, VmThread t, Frame f, ClassFile.Ref r, int pc,
			boolean isPut) {
		if (isPut) {
			Object v = f.stack[--f.sp];
			if (v == Frame.WIDE)
				v = f.stack[--f.sp];
			Object recv = f.stack[--f.sp];
			if (recv == null)
				throw new VmException(new NullPointerException());
			if (recv instanceof VmObject) {
				VmClass owner = vm.resolveClass(r.owner);
				Integer slot = owner.fieldSlot(r.name, r.desc);
				if (slot == null)
					throw new VmException(new NoSuchFieldError(r.name));
				((VmObject) recv).fields[slot] = v;
			} else {
				vm.bridge.putField(recv, r, v);
			}
			f.pc = pc + 3;
			return;
		}
		Object recv = f.stack[--f.sp];
		if (recv == null)
			throw new VmException(new NullPointerException());
		Object v;
		if (recv instanceof VmObject) {
			VmClass owner = vm.resolveClass(r.owner);
			Integer slot = owner.fieldSlot(r.name, r.desc);
			if (slot == null)
				throw new VmException(new NoSuchFieldError(r.name));
			v = ((VmObject) recv).fields[slot];
		} else {
			v = vm.bridge.getField(recv, r);
		}
		f.stack[f.sp++] = v;
		if (VmClass.isWide(r.desc))
			f.stack[f.sp++] = Frame.WIDE;
		f.pc = pc + 3;
	}

	// ---------- helpers ----------

	private static int u2(byte[] c, int i) {
		return ((c[i] & 0xff) << 8) | (c[i + 1] & 0xff);
	}

	private static int s2(byte[] c, int i) {
		return (short) (((c[i] & 0xff) << 8) | (c[i + 1] & 0xff));
	}

	private static int s4(byte[] c, int i) {
		return (c[i] << 24) | ((c[i + 1] & 0xff) << 16) | ((c[i + 2] & 0xff) << 8)
				| (c[i + 3] & 0xff);
	}

	private static void ldc(Vm vm, Frame f, ClassFile cf, int idx) {
		if (cf.cpTag(idx) == 7) {
			VmClass c = vm.resolveClass(cf.className(idx));
			f.stack[f.sp++] = c.host ? (Object) c.hostClass : c.guestClass();
		} else {
			Object v = cf.cpValue(idx);
			if (v instanceof String)
				v = vm.intern((String) v);
			f.stack[f.sp++] = v;
		}
	}

	private static void pushWide(Object[] st, int[] sp, Object v) {
		st[sp[0]++] = v;
		st[sp[0]++] = Frame.WIDE;
	}

	// ---------- the step ----------

	public static volatile boolean TRACE = false;

	private static void step(Vm vm, VmThread t, Frame f) {
		byte[] code = f.method.code.code;
		ClassFile cf = f.owner.cf;
		int pc = f.pc;
		int op = code[pc] & 0xff;
		Object[] st = f.stack, lo = f.locals;
		int sp = f.sp;
		f.sp = sp; // keep frame in sync for helpers that use f.sp directly
		if (TRACE)
			System.err.printf("%-24s pc=%-4d op=%02x sp=%d stack=%s%n",
					f.owner.name + "." + f.method.name, pc, op, sp,
					java.util.Arrays.toString(java.util.Arrays.copyOf(st, Math.min(sp, 8))));

		switch (op) {
			// const
			case 0x00: f.pc = pc + 1; return;
			case 0x01: st[sp++] = null; break;
			case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08:
				st[sp++] = op - 0x03; break;
			case 0x09: case 0x0a:
				st[sp++] = (long) (op - 0x09); st[sp++] = Frame.WIDE; break;
			case 0x0b: case 0x0c: case 0x0d:
				st[sp++] = (float) (op - 0x0b); break;
			case 0x0e: case 0x0f:
				st[sp++] = (double) (op - 0x0e); st[sp++] = Frame.WIDE; break;
			case 0x10: st[sp++] = (int) code[pc + 1]; f.pc = pc + 2; f.sp = sp; return;
			case 0x11: st[sp++] = s2(code, pc + 1); f.pc = pc + 3; f.sp = sp; return;
			case 0x12: f.sp = sp; ldc(vm, f, cf, code[pc + 1] & 0xff); f.pc = pc + 2; return;
			case 0x13: f.sp = sp; ldc(vm, f, cf, u2(code, pc + 1)); f.pc = pc + 3; return;
			case 0x14:
				st[sp++] = cf.cpValue(u2(code, pc + 1)); st[sp++] = Frame.WIDE;
				f.pc = pc + 3; f.sp = sp; return;
			// xload n
			case 0x15: case 0x17: case 0x19:
				st[sp++] = lo[code[pc + 1] & 0xff]; f.pc = pc + 2; f.sp = sp; return;
			case 0x16: case 0x18:
				st[sp++] = lo[code[pc + 1] & 0xff]; st[sp++] = Frame.WIDE;
				f.pc = pc + 2; f.sp = sp; return;
			// xload_0..3
			case 0x1a: case 0x1b: case 0x1c: case 0x1d:
				st[sp++] = lo[op - 0x1a]; break;
			case 0x1e: case 0x1f: case 0x20: case 0x21:
				st[sp++] = lo[op - 0x1e]; st[sp++] = Frame.WIDE; break;
			case 0x22: case 0x23: case 0x24: case 0x25:
				st[sp++] = lo[op - 0x22]; break;
			case 0x26: case 0x27: case 0x28: case 0x29:
				st[sp++] = lo[op - 0x26]; st[sp++] = Frame.WIDE; break;
			case 0x2a: case 0x2b: case 0x2c: case 0x2d:
				st[sp++] = lo[op - 0x2a]; break;
			// array loads
			case 0x2e: case 0x30: case 0x32: case 0x33: case 0x34: case 0x35: {
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = Array.get(arr, idx);
				break;
			}
			case 0x2f: case 0x31: {
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = Array.get(arr, idx);
				st[sp++] = Frame.WIDE;
				break;
			}
			// xstore n
			case 0x36: case 0x38: case 0x3a: {
				int n = code[pc + 1] & 0xff;
				lo[n] = st[--sp];
				f.pc = pc + 2; f.sp = sp; return;
			}
			case 0x37: case 0x39: {
				int n = code[pc + 1] & 0xff;
				lo[n + 1] = Frame.WIDE;
				lo[n] = st[sp - 2];
				sp -= 2;
				f.pc = pc + 2; f.sp = sp; return;
			}
			// xstore_0..3
			case 0x3b: case 0x3c: case 0x3d: case 0x3e:
				lo[op - 0x3b] = st[--sp]; break;
			case 0x3f: case 0x40: case 0x41: case 0x42: {
				int n = op - 0x3f;
				lo[n + 1] = Frame.WIDE; lo[n] = st[sp - 2]; sp -= 2;
				break;
			}
			case 0x43: case 0x44: case 0x45: case 0x46:
				lo[op - 0x43] = st[--sp]; break;
			case 0x47: case 0x48: case 0x49: case 0x4a: {
				int n = op - 0x47;
				lo[n + 1] = Frame.WIDE; lo[n] = st[sp - 2]; sp -= 2;
				break;
			}
			case 0x4b: case 0x4c: case 0x4d: case 0x4e:
				lo[op - 0x4b] = st[--sp]; break;
			// array stores
			case 0x4f: case 0x51: case 0x53: case 0x54: case 0x55: {
				Object v = st[--sp];
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				storeArray(arr, idx, v);
				break;
			}
			case 0x50: case 0x52: {
				Object v = st[sp - 2];
				sp -= 2;
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				storeArray(arr, idx, v);
				break;
			}
			case 0x56: { // aastore
				Object v = st[--sp];
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				storeArrayChecked(vm, arr, idx, v);
				break;
			}
			// stack ops
			case 0x57: sp--; break;
			case 0x58: sp--; if (st[sp] == Frame.WIDE) sp--; break; // pop2
			case 0x59: st[sp] = st[sp - 1]; sp++; break;             // dup
			case 0x5a: {                                             // dup_x1
				Object b = st[sp - 1], a = st[sp - 2];
				st[sp - 2] = b; st[sp - 1] = a; st[sp++] = b;
				break;
			}
			case 0x5b: {                                             // dup_x2
				if (st[sp - 2] == Frame.WIDE) {
					Object b = st[sp - 1], v = st[sp - 3];
					st[sp - 3] = b; st[sp - 2] = v; st[sp - 1] = Frame.WIDE; st[sp++] = b;
				} else {
					Object c = st[sp - 1], b = st[sp - 2], a = st[sp - 3];
					st[sp - 3] = c; st[sp - 2] = a; st[sp - 1] = b; st[sp++] = c;
				}
				break;
			}
			case 0x5c:                                               // dup2
				if (st[sp - 1] == Frame.WIDE) {
					st[sp] = st[sp - 2]; st[sp + 1] = Frame.WIDE; sp += 2;
				} else {
					st[sp] = st[sp - 2]; st[sp + 1] = st[sp - 1]; sp += 2;
				}
				break;
			case 0x5d: {                                             // dup2_x1
				if (st[sp - 1] == Frame.WIDE) { // wide over single
					Object v = st[sp - 2], a = st[sp - 3];
					st[sp - 3] = v; st[sp - 2] = Frame.WIDE; st[sp - 1] = a;
					st[sp] = v; st[sp + 1] = Frame.WIDE; sp += 2;
				} else {
					Object c = st[sp - 1], b = st[sp - 2], a = st[sp - 3];
					st[sp - 3] = b; st[sp - 2] = c; st[sp - 1] = a;
					st[sp] = b; st[sp + 1] = c; sp += 2;
				}
				break;
			}
			case 0x5e: {                                             // dup2_x2
				if (st[sp - 1] == Frame.WIDE) {
					if (st[sp - 3] == Frame.WIDE) { // wide over wide
						Object v2 = st[sp - 2], v1 = st[sp - 4];
						st[sp - 4] = v2; st[sp - 3] = Frame.WIDE;
						st[sp - 2] = v1; st[sp - 1] = Frame.WIDE;
						st[sp] = v2; st[sp + 1] = Frame.WIDE; sp += 2;
					} else { // wide over two singles
						Object v = st[sp - 2], b = st[sp - 3], a = st[sp - 4];
						st[sp - 4] = v; st[sp - 3] = Frame.WIDE;
						st[sp - 2] = a; st[sp - 1] = b;
						st[sp] = v; st[sp + 1] = Frame.WIDE; sp += 2;
					}
				} else if (st[sp - 3] == Frame.WIDE) { // two singles over wide
					Object c = st[sp - 1], b = st[sp - 2], v = st[sp - 5];
					st[sp - 5] = b; st[sp - 4] = c;
					st[sp - 3] = v; st[sp - 2] = Frame.WIDE;
					st[sp - 1] = b; st[sp] = c; sp += 2;
				} else { // two singles over two singles
					Object d = st[sp - 1], c = st[sp - 2], b = st[sp - 3], a = st[sp - 4];
					st[sp - 4] = c; st[sp - 3] = d; st[sp - 2] = a; st[sp - 1] = b;
					st[sp] = c; st[sp + 1] = d; sp += 2;
				}
				break;
			}
			case 0x5f: {                                             // swap
				Object a = st[sp - 1];
				st[sp - 1] = st[sp - 2]; st[sp - 2] = a;
				break;
			}
			// arithmetic int
			case 0x60: st[sp - 2] = (Integer) st[sp - 2] + (Integer) st[sp - 1]; sp--; break;
			case 0x61: st[sp - 4] = (Long) st[sp - 4] + (Long) st[sp - 2]; sp -= 2; break;
			case 0x62: st[sp - 2] = (Float) st[sp - 2] + (Float) st[sp - 1]; sp--; break;
			case 0x63: st[sp - 4] = (Double) st[sp - 4] + (Double) st[sp - 2]; sp -= 2; break;
			case 0x64: st[sp - 2] = (Integer) st[sp - 2] - (Integer) st[sp - 1]; sp--; break;
			case 0x65: st[sp - 4] = (Long) st[sp - 4] - (Long) st[sp - 2]; sp -= 2; break;
			case 0x66: st[sp - 2] = (Float) st[sp - 2] - (Float) st[sp - 1]; sp--; break;
			case 0x67: st[sp - 4] = (Double) st[sp - 4] - (Double) st[sp - 2]; sp -= 2; break;
			case 0x68: st[sp - 2] = (Integer) st[sp - 2] * (Integer) st[sp - 1]; sp--; break;
			case 0x69: st[sp - 4] = (Long) st[sp - 4] * (Long) st[sp - 2]; sp -= 2; break;
			case 0x6a: st[sp - 2] = (Float) st[sp - 2] * (Float) st[sp - 1]; sp--; break;
			case 0x6b: st[sp - 4] = (Double) st[sp - 4] * (Double) st[sp - 2]; sp -= 2; break;
			case 0x6c: { int b = (Integer) st[--sp], a = (Integer) st[--sp]; st[sp++] = idiv(a, b); break; }
			case 0x6d: { long b = (Long) st[sp - 2], a = (Long) st[sp - 4]; sp -= 4;
				st[sp++] = ldiv(a, b); st[sp++] = Frame.WIDE; break; }
			case 0x6e: st[sp - 2] = (Float) st[sp - 2] / (Float) st[sp - 1]; sp--; break;
			case 0x6f: st[sp - 4] = (Double) st[sp - 4] / (Double) st[sp - 2]; sp -= 2; break;
			case 0x70: { int b = (Integer) st[--sp], a = (Integer) st[--sp]; st[sp++] = irem(a, b); break; }
			case 0x71: { long b = (Long) st[sp - 2], a = (Long) st[sp - 4]; sp -= 4;
				st[sp++] = lrem(a, b); st[sp++] = Frame.WIDE; break; }
			case 0x72: st[sp - 2] = (Float) st[sp - 2] % (Float) st[sp - 1]; sp--; break;
			case 0x73: st[sp - 4] = (Double) st[sp - 4] % (Double) st[sp - 2]; sp -= 2; break;
			case 0x74: st[sp - 1] = -(Integer) st[sp - 1]; break;
			case 0x75: st[sp - 2] = -(Long) st[sp - 2]; break;
			case 0x76: st[sp - 1] = -(Float) st[sp - 1]; break;
			case 0x77: st[sp - 2] = -(Double) st[sp - 2]; break;
			// shifts
			case 0x78: st[sp - 2] = (Integer) st[sp - 2] << (Integer) st[sp - 1]; sp--; break;
			case 0x79: st[sp - 3] = (Long) st[sp - 3] << (Integer) st[sp - 1]; sp -= 2; break;
			case 0x7a: st[sp - 2] = (Integer) st[sp - 2] >> (Integer) st[sp - 1]; sp--; break;
			case 0x7b: st[sp - 3] = (Long) st[sp - 3] >> (Integer) st[sp - 1]; sp -= 2; break;
			case 0x7c: st[sp - 2] = (Integer) st[sp - 2] >>> (Integer) st[sp - 1]; sp--; break;
			case 0x7d: st[sp - 3] = (Long) st[sp - 3] >>> (Integer) st[sp - 1]; sp -= 2; break;
			// logic
			case 0x7e: st[sp - 2] = (Integer) st[sp - 2] & (Integer) st[sp - 1]; sp--; break;
			case 0x7f: st[sp - 4] = (Long) st[sp - 4] & (Long) st[sp - 2]; sp -= 2; break;
			case 0x80: st[sp - 2] = (Integer) st[sp - 2] | (Integer) st[sp - 1]; sp--; break;
			case 0x81: st[sp - 4] = (Long) st[sp - 4] | (Long) st[sp - 2]; sp -= 2; break;
			case 0x82: st[sp - 2] = (Integer) st[sp - 2] ^ (Integer) st[sp - 1]; sp--; break;
			case 0x83: st[sp - 4] = (Long) st[sp - 4] ^ (Long) st[sp - 2]; sp -= 2; break;
			case 0x84: { // iinc
				int n = code[pc + 1] & 0xff;
				lo[n] = (Integer) lo[n] + code[pc + 2];
				f.pc = pc + 3; f.sp = sp; return;
			}
			// conversions
			case 0x85: st[sp - 1] = (long) (Integer) st[sp - 1]; insWide(st, sp); sp++; break;
			case 0x86: st[sp - 1] = (float) (Integer) st[sp - 1]; break;
			case 0x87: st[sp - 1] = (double) (Integer) st[sp - 1]; insWide(st, sp); sp++; break;
			case 0x88: st[sp - 2] = (int) (long) st[sp - 2]; sp--; break;
			case 0x89: st[sp - 2] = (float) (long) st[sp - 2]; sp--; break;
			case 0x8a: st[sp - 2] = (double) (long) st[sp - 2]; break;
			case 0x8b: st[sp - 1] = (int) (float) (Float) st[sp - 1]; break;
			case 0x8c: st[sp - 1] = (long) (float) (Float) st[sp - 1]; insWide(st, sp); sp++; break;
			case 0x8d: st[sp - 1] = (double) (float) (Float) st[sp - 1]; insWide(st, sp); sp++; break;
			case 0x8e: st[sp - 2] = (int) (double) (Double) st[sp - 2]; sp--; break;
			case 0x8f: st[sp - 2] = (long) (double) (Double) st[sp - 2]; break;
			case 0x90: st[sp - 2] = (float) (double) (Double) st[sp - 2]; sp--; break;
			case 0x91: st[sp - 1] = (int) (byte) (int) (Integer) st[sp - 1]; break;
			case 0x92: st[sp - 1] = (int) (char) (int) (Integer) st[sp - 1]; break;
			case 0x93: st[sp - 1] = (int) (short) (int) (Integer) st[sp - 1]; break;
			// comparisons
			case 0x94: { long b = (Long) st[sp - 2], a = (Long) st[sp - 4]; sp -= 4;
				st[sp++] = a < b ? -1 : a == b ? 0 : 1; break; }
			case 0x95: case 0x96: { float b = (Float) st[--sp], a = (Float) st[--sp];
				st[sp++] = (Float.isNaN(a) || Float.isNaN(b)) ? (op == 0x95 ? -1 : 1)
						: Float.compare(a, b);
				break; }
			case 0x97: case 0x98: { double b = (Double) st[sp - 2], a = (Double) st[sp - 4]; sp -= 4;
				st[sp++] = (Double.isNaN(a) || Double.isNaN(b)) ? (op == 0x97 ? -1 : 1)
						: Double.compare(a, b);
				break; }
			// branches
			case 0x99: case 0x9a: case 0x9b: case 0x9c: case 0x9d: case 0x9e: {
				int v = (Integer) st[--sp];
				boolean go = op == 0x99 ? v == 0 : op == 0x9a ? v != 0 : op == 0x9b ? v < 0
						: op == 0x9c ? v >= 0 : op == 0x9d ? v > 0 : v <= 0;
				f.pc = go ? pc + s2(code, pc + 1) : pc + 3;
				f.sp = sp; return;
			}
			case 0x9f: case 0xa0: case 0xa1: case 0xa2: case 0xa3: case 0xa4: {
				int b = (Integer) st[--sp], a = (Integer) st[--sp];
				boolean go = op == 0x9f ? a == b : op == 0xa0 ? a != b : op == 0xa1 ? a < b
						: op == 0xa2 ? a >= b : op == 0xa3 ? a > b : a <= b;
				f.pc = go ? pc + s2(code, pc + 1) : pc + 3;
				f.sp = sp; return;
			}
			case 0xa5: case 0xa6: {
				Object b = st[--sp], a = st[--sp];
				boolean go = (op == 0xa5) == (a == b);
				f.pc = go ? pc + s2(code, pc + 1) : pc + 3;
				f.sp = sp; return;
			}
			case 0xa7: f.pc = pc + s2(code, pc + 1); f.sp = sp; return;
			case 0xa8: case 0xa9:                                      // jsr/ret
				throw new VmError("jsr/ret not supported");
			case 0xaa: { // tableswitch
				int idx = (Integer) st[--sp];
				int base = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
				int def = s4(code, base);
				int low = s4(code, base + 4), high = s4(code, base + 8);
				int off = (idx >= low && idx <= high)
						? s4(code, base + 12 + (idx - low) * 4) : def;
				f.pc = pc + off;
				f.sp = sp; return;
			}
			case 0xab: { // lookupswitch
				int key = (Integer) st[--sp];
				int base = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
				int def = s4(code, base);
				int npairs = s4(code, base + 4);
				int off = def;
				for (int i = 0; i < npairs; i++) {
					if (s4(code, base + 8 + i * 8) == key) {
						off = s4(code, base + 12 + i * 8);
						break;
					}
				}
				f.pc = pc + off;
				f.sp = sp; return;
			}
			// returns
			case 0xac: case 0xae: case 0xb0: { // ireturn/freturn/areturn
				f.sp = sp;
				doReturnTyped(t, f);
				return;
			}
			case 0xad: case 0xaf: { // lreturn/dreturn
				f.sp = sp;
				doReturnWide(t, f);
				return;
			}
			case 0xb1: f.sp = sp; doReturnVoid(t); return;
			// field access
			case 0xb2: f.sp = sp; getPutStatic(vm, t, f, ref(cf, u2(code, pc + 1)), pc, false); return;
			case 0xb3: f.sp = sp; getPutStatic(vm, t, f, ref(cf, u2(code, pc + 1)), pc, true); return;
			case 0xb4: f.sp = sp; getPutField(vm, t, f, ref(cf, u2(code, pc + 1)), pc, false); return;
			case 0xb5: f.sp = sp; getPutField(vm, t, f, ref(cf, u2(code, pc + 1)), pc, true); return;
			// invokes
			case 0xb6: f.sp = sp; invoke(vm, t, f, ref(cf, u2(code, pc + 1)), pc, 3, 0); return;
			case 0xb7: f.sp = sp; invoke(vm, t, f, ref(cf, u2(code, pc + 1)), pc, 3, 2); return;
			case 0xb8: f.sp = sp; invoke(vm, t, f, ref(cf, u2(code, pc + 1)), pc, 3, 1); return;
			case 0xb9: f.sp = sp; invoke(vm, t, f, ref(cf, u2(code, pc + 1)), pc, 5, 0); return;
			case 0xba: throw new VmError("invokedynamic not supported");
			// new/arrays
			case 0xbb: { // new
				VmClass c = vm.resolveClass(cf.className(u2(code, pc + 1)));
				if (c.host)
					st[sp++] = new HostBridge.NewMarker(c);
				else {
					if (!vm.ensureInit(c, t)) { f.sp = sp; return; }
					st[sp++] = new VmObject(c);
				}
				f.pc = pc + 3;
				f.sp = sp; return;
			}
			case 0xbc: { // newarray
				int n = (Integer) st[--sp];
				int at = code[pc + 1] & 0xff;
				st[sp++] = newPrimArray(at, n);
				f.pc = pc + 2;
				f.sp = sp; return;
			}
			case 0xbd: { // anewarray
				int n = (Integer) st[--sp];
				VmClass c = vm.resolveClass(cf.className(u2(code, pc + 1)));
				st[sp++] = newObjArray(vm, c, n);
				f.pc = pc + 3;
				f.sp = sp; return;
			}
			case 0xbe: { // arraylength
				Object arr = st[--sp];
				if (arr == null)
					throw new VmException(new NullPointerException());
				st[sp++] = Array.getLength(arr);
				break;
			}
			case 0xbf: { // athrow
				Object ex = st[--sp];
				if (ex == null)
					ex = new NullPointerException();
				throw new VmException(ex);
			}
			case 0xc0: { // checkcast
				Object o = st[sp - 1];
				if (o != null) {
					VmClass c = vm.resolveClass(cf.className(u2(code, pc + 1)));
					if (!c.isInstance(o))
						throw new VmException(new ClassCastException(
								o.getClass().getName() + " -> " + c.name));
				}
				f.pc = pc + 3;
				f.sp = sp; return;
			}
			case 0xc1: { // instanceof
				Object o = st[--sp];
				VmClass c = vm.resolveClass(cf.className(u2(code, pc + 1)));
				st[sp++] = o != null && c.isInstance(o) ? 1 : 0;
				f.pc = pc + 3;
				f.sp = sp; return;
			}
			case 0xc2: { // monitorenter
				Object o = st[--sp];
				if (o == null)
					throw new VmException(new NullPointerException());
				if (!vm.monEnter(t, o)) {
					sp--; // consumed the ref; park without advancing
					f.sp = sp;
					return;
				}
				break;
			}
			case 0xc3: { // monitorexit
				Object o = st[--sp];
				if (o == null)
					throw new VmException(new NullPointerException());
				vm.monExit(t, o);
				break;
			}
			case 0xc4: { // wide
				int wop = code[pc + 1] & 0xff;
				int n = u2(code, pc + 2);
				if (wop == 0x84) {
					lo[n] = (Integer) lo[n] + s2(code, pc + 4);
					f.pc = pc + 6;
				} else {
					// wide load/store
					switch (wop) {
						case 0x15: case 0x17: case 0x19:
							st[sp++] = lo[n]; break;
						case 0x16: case 0x18:
							st[sp++] = lo[n]; st[sp++] = Frame.WIDE; break;
						case 0x36: case 0x38: case 0x3a:
							lo[n] = st[--sp]; break;
						case 0x37: case 0x39:
							lo[n + 1] = Frame.WIDE; lo[n] = st[sp - 2]; sp -= 2; break;
						default:
							throw new VmError("bad wide op " + wop);
					}
					f.pc = pc + 4;
				}
				f.sp = sp;
				return;
			}
			case 0xc5: { // multianewarray
				int dims = code[pc + 3] & 0xff;
				VmClass c = vm.resolveClass(cf.className(u2(code, pc + 1)));
				int[] sizes = new int[dims];
				for (int i = dims - 1; i >= 0; i--)
					sizes[i] = (Integer) st[--sp];
				st[sp++] = multiArray(c.name, sizes, 0);
				f.pc = pc + 4;
				f.sp = sp; return;
			}
			case 0xc6: { // ifnull
				Object o = st[--sp];
				f.pc = o == null ? pc + s2(code, pc + 1) : pc + 3;
				f.sp = sp; return;
			}
			case 0xc7: { // ifnonnull
				Object o = st[--sp];
				f.pc = o != null ? pc + s2(code, pc + 1) : pc + 3;
				f.sp = sp; return;
			}
			case 0xc8: f.pc = pc + s4(code, pc + 1); f.sp = sp; return;  // goto_w
			case 0xc9: throw new VmError("jsr_w not supported");
			default:
				throw new VmError("unimplemented opcode 0x" + Integer.toHexString(op)
						+ " in " + f.describe());
		}
		f.pc = pc + 1;
		f.sp = sp;
	}

	private static ClassFile.Ref ref(ClassFile cf, int idx) {
		return (ClassFile.Ref) cf.cpValue(idx);
	}

	private static void doReturnTyped(VmThread t, Frame f) {
		Object ret = f.stack[--f.sp];
		finishClinitIfNeeded(f);
		t.frames.remove(t.frames.size() - 1);
		if (t.frames.isEmpty()) {
			t.result = ret;
			t.terminate();
			return;
		}
		t.nestedResult = ret;
		Frame caller = t.top();
		caller.stack[caller.sp++] = ret;
	}

	private static void doReturnWide(VmThread t, Frame f) {
		Object ret = f.stack[f.sp - 2];
		finishClinitIfNeeded(f);
		t.frames.remove(t.frames.size() - 1);
		if (t.frames.isEmpty()) {
			t.result = ret;
			t.terminate();
			return;
		}
		t.nestedResult = ret;
		Frame caller = t.top();
		caller.stack[caller.sp++] = ret;
		caller.stack[caller.sp++] = Frame.WIDE;
	}

	private static void doReturnVoid(VmThread t) {
		Frame f = t.top();
		finishClinitIfNeeded(f);
		t.frames.remove(t.frames.size() - 1);
		if (t.frames.isEmpty()) {
			t.terminate();
			return;
		}
		t.nestedResult = null;
	}

	private static void storeArray(Object arr, int idx, Object v) {
		if (arr == null)
			throw new VmException(new NullPointerException());
		try {
			Array.set(arr, idx, v);
		} catch (IllegalArgumentException e) {
			throw new VmException(new ArrayStoreException());
		}
	}

	private static void storeArrayChecked(Vm vm, Object arr, int idx, Object v) {
		if (arr == null)
			throw new VmException(new NullPointerException());
		if (v instanceof VmObject) {
			Class<?> ct = arr.getClass().getComponentType();
			if (ct != null && ct != Object.class)
				throw new VmException(new ArrayStoreException("guest obj into " + ct));
			VmClass comp = vm.guestArrayTypes.get(arr);
			if (comp != null && !comp.isInstance(v))
				throw new VmException(new ArrayStoreException());
		}
		if (v == null) {
			Array.set(arr, idx, null);
			return;
		}
		try {
			Array.set(arr, idx, v);
		} catch (IllegalArgumentException e) {
			throw new VmException(new ArrayStoreException());
		}
	}

	private static Object newPrimArray(int at, int n) {
		switch (at) {
			case 4: return new boolean[n];
			case 5: return new char[n];
			case 6: return new float[n];
			case 7: return new double[n];
			case 8: return new byte[n];
			case 9: return new short[n];
			case 10: return new int[n];
			case 11: return new long[n];
			default: throw new VmError("bad atype " + at);
		}
	}

	private static Object newObjArray(Vm vm, VmClass comp, int n) {
		if (comp.host)
			return Array.newInstance(comp.hostClass, n);
		Object[] a = new Object[n];
		vm.guestArrayTypes.put(a, comp);
		return a;
	}

	private static Object multiArray(String desc, int[] sizes, int d) {
		// desc like "[[I" — component for this level
		int n = sizes[d];
		if (d == sizes.length - 1) {
			String elem = desc.substring(d);
			switch (elem.charAt(0)) {
				case 'I': return new int[n];
				case 'J': return new long[n];
				case 'F': return new float[n];
				case 'D': return new double[n];
				case 'B': return new byte[n];
				case 'C': return new char[n];
				case 'S': return new short[n];
				case 'Z': return new boolean[n];
				default:
					Object[] a = new Object[n];
					for (int i = 0; i < n; i++) a[i] = null;
					return a;
			}
		}
		Object[] a = new Object[n];
		for (int i = 0; i < n; i++)
			a[i] = multiArray(desc, sizes, d + 1);
		return a;
	}

	private static void insWide(Object[] st, int sp) {
		st[sp] = Frame.WIDE;
	}

	private static int idiv(int a, int b) {
		if (b == 0)
			throw new VmException(new ArithmeticException("/ by zero"));
		return a / b;
	}

	private static long ldiv(long a, long b) {
		if (b == 0)
			throw new VmException(new ArithmeticException("/ by zero"));
		return a / b;
	}

	private static int irem(int a, int b) {
		if (b == 0)
			throw new VmException(new ArithmeticException("/ by zero"));
		return a % b;
	}

	private static long lrem(long a, long b) {
		if (b == 0)
			throw new VmException(new ArithmeticException("/ by zero"));
		return a % b;
	}
}
