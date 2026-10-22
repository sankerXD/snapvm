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
			if (t.joinTarget != null) {
				// back from join() — leave the target's waiter list so a later
				// terminate() can't spuriously wake this thread mid-other-park
				t.joinTarget.removeJoinWaiter(t);
				t.joinTarget = null;
			}
			if (t.pendingInterrupt) {
				t.pendingInterrupt = false;
				t.interrupted = false; // delivering clears the interrupt status
				if (t.interruptPc >= 0)
					f.pc = t.interruptPc; // rewind to the interruptible call so catch ranges match
				t.interruptPc = -1;
				throwObject(vm, t, new InterruptedException(), depth);
				continue;
			}
			try {
				int r = step(vm, t, f,
						(int) Math.min(budget - used, Integer.MAX_VALUE), depth);
				used += r;
				Frame top = t.top();
				if (top != null && (top.ops += r) > RUNAWAY_OPS)
					throw new VmException(new InternalError(
							"runaway frame " + top.describe()
									+ " — guest loop " + top.ops + " ops"));
			} catch (VmException e) {
				throwObject(vm, t, e.thrown, depth);
			} catch (RuntimeException e) {
				// host NPE/CCE/AIOOBE from helpers: surface to guest as throwable
				throwObject(vm, t, e, depth);
			}
		}
		return used;
	}

	/** Re-acquire a monitor for a thread resuming from wait()/contention. */
	private static boolean reacquire(VmThread t) {
		Object mo = t.waitMonitor;
		if (!(mo instanceof Vm.Monitor)) {
			// clinit gate parks on the VmClass itself; nothing to reacquire
			t.waitMonitor = null;
			return true;
		}
		Vm.Monitor m = (Vm.Monitor) mo;
		synchronized (m) {
			if (m.owner == null || m.owner == t) {
				if (t.resumingFromWait) {
					// the wait() call site already advanced; grab the monitor
					// here so resumption sees it held at the released depth
					m.waiters.remove(t);
					m.owner = t;
					m.count = Math.max(1, t.waitReleasedCount);
					t.waitReleasedCount = 0;
					t.resumingFromWait = false;
				}
				t.waitMonitor = null;
				return true;
			}
			t.state = VmThread.BLOCKED;
			// lost-wakeup guard: the contender entry may have been pruned while
			// this thread was RUNNABLE between wake and reacquire — re-list it
			if (!m.contenders.contains(t))
				m.contenders.add(t);
			return false;
		}
	}

	// ---------- guest-level throw ----------

	static void throwObject(Vm vm, VmThread t, Object thrown, int depth) {
		String trace = trace(t); // capture before frames are popped
		if (TRACE_THROWS && !t.frames.isEmpty()) {
			Frame top = t.top();
			String key = thrown + "@" + top.owner.name + "." + top.method.name
					+ "@" + top.pc;
			if (TRACE_SEEN.add("T" + key)) {
				System.err.println("GuestThrow " + thrown + " at "
						+ top.owner.name + "." + top.method.name + "@" + top.pc);
				if (thrown instanceof Throwable)
					((Throwable) thrown).printStackTrace();
			}
		}
		boolean callerCallSite = false;
		// nested-call boundary: frames at/below depth belong to the enclosing
		// guest call site, not to this invocation — never unwind into them
		// (a real VM runs e.g. serviceRepaints' paints on the event thread, so
		// a throwing paint can never pop the caller's frames either)
		int keep = Math.max(depth, 0);
		while (t.frames.size() > keep) {
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
			boolean boundary = f.external;
			popFrame(t, f);
			if (boundary && t.frames.size() > keep) {
				// popped an external frame (pushInvoke/pushStatic entry) while
				// the caller's frames are still below — this throw belongs to
				// the nested call only. Deferred host→guest work (e.g. a paint
				// pushed mid-invoke) dies here instead of eating the thread.
				VmException ve = new VmException(thrown);
				ve.vmTrace = trace;
				t.nestedError = ve;
				System.err.println("SnapVM nested uncaught " + ve + trace);
				return;
			}
		}
		VmException ve = new VmException(thrown);
		ve.vmTrace = trace;
		if (t.frames.isEmpty()) {
			t.lastError = ve;
			t.terminate();
			// uncaught guest exceptions dump the guest trace — this is the only
			// stack a host-side crash report will ever see
			System.err.println("SnapVM uncaught " + ve + trace);
		} else {
			// unwind stopped at a nested boundary: the nested call died, the
			// caller thread stays alive; its host caller rethrows this
			t.nestedError = ve;
			System.err.println("SnapVM nested uncaught " + ve + trace);
		}
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
		if (TRACE_CALLS != null) {
			if (r.traceCallMatch == 0)
				r.traceCallMatch = (byte) (traceMatches(r.owner + "." + r.name + r.desc) ? 2 : 1);
			if (r.traceCallMatch == 2
					&& TRACE_SEEN.add("C" + r.owner + "." + r.name + r.desc
							+ "<-" + f.owner.name + "." + f.method.name + "@" + pc))
				System.err.println("GuestCall " + r.owner + "." + r.name + r.desc
						+ " from " + f.owner.name + "." + f.method.name + "@" + pc);
		}
		if (TRACE_ARGS != null) {
			if (r.traceArgsMatch == 0)
				r.traceArgsMatch = (byte) (traceArgsMatches(r.owner + "." + r.name + r.desc) ? 2 : 1);
			if (r.traceArgsMatch == 2) {
				StringBuilder sb = new StringBuilder("GuestArgs ")
						.append(r.owner).append('.').append(r.name).append(r.desc)
						.append(" from ").append(f.owner.name).append('.')
						.append(f.method.name).append('@').append(pc).append(" [");
				int base = f.sp - r.argSlots() - (kind == 1 ? 0 : 1);
				for (int i = Math.max(0, base); i < f.sp; i++) {
					Object v = f.stack[i];
					sb.append(v instanceof byte[] ? "byte[" + ((byte[]) v).length + "]"
							: v instanceof int[] ? "int[" + ((int[]) v).length + "]"
							: v instanceof short[] ? "short[" + ((short[]) v).length + "]"
							: String.valueOf(v)).append(',');
				}
				System.err.println(sb.append(']'));
			}
		}
		VmClass owner = r.ownerClass;
		if (owner == null)
			r.ownerClass = owner = vm.resolveClass(r.owner);
		if (kind == 1) {
			if (owner.host) {
				hostInvoke(vm, t, f, r, pc, oplen, 1);
				return;
			}
			if (!vm.ensureInit(owner, t))
				return; // clinit frame pushed: retry instruction next step
			ClassFile.Method m = owner.findDeclared(r.key());
			if (m == null)
				throw new VmException(new NoSuchMethodError(owner.name + "." + r.name + r.desc));
			if (m.code == null)
				throw new VmException(new AbstractMethodError(owner.name + "." + r.name));
			VmClass decl = declaring(owner, m);
			Object syncOn = null;
			if ((m.access & 0x0020) != 0) { // ACC_SYNCHRONIZED: lock the class
				syncOn = decl.guestClass();
				if (!vm.monEnter(t, syncOn))
					return; // park; the whole invoke retries on wake
			}
			pushGuestFrame(t, f, decl, m, r, pc, oplen, false, syncOn);
			return;
		}

		Object recv = f.stack[f.sp - r.argSlots() - 1];
		if (kind == 2) {
			// invokespecial
			if (recv instanceof HostBridge.NewMarker) {
				HostBridge.NewMarker mk = (HostBridge.NewMarker) recv;
				Object[] args = popToArray(f, r, false);
				f.sp--; // pop marker
				Object built = vm.bridge.call(t, mk.cls.name, "<init>", r.desc, args, 3);
				replaceMarker(t, mk, built);
				f.pc = pc + oplen;
				return;
			}
			if (recv == null)
				throw new VmException(new NullPointerException());
			if (owner.host) {
				if (r.name.equals("<init>") && recv instanceof VmObject) {
					// host superclass constructor: manifests the adapter shadow
					// (its own ctor runs the real host super-ctor)
					Object[] initArgs = popToArray(f, r, true);
					VmObject g = (VmObject) recv;
					if (g.superCtorArgs == null)
						g.superCtorArgs = initArgs;
					vm.constructShadow(g, owner);
					if (g.exceptionTrace == null
							&& Throwable.class.isAssignableFrom(owner.hostClass))
						g.exceptionTrace = trace(t);
					f.pc = pc + oplen;
					return;
				}
				hostInvoke(vm, t, f, r, pc, oplen, 2);
				return;
			}
			// <init> is not inherited — supers walk would run a parent's ctor
			// under this class's constant pool; plain invokespecial does walk
			ClassFile.Method m = r.name.equals("<init>")
					? owner.declaredMethods.get(r.key())
					: owner.findDeclared(r.key());
			if (m == null || m.code == null)
				throw new VmException(new AbstractMethodError(owner.name + "." + r.name));
			Object syncOn = null;
			if ((m.access & 0x0020) != 0) { // ACC_SYNCHRONIZED: lock the receiver
				syncOn = recv;
				if (!vm.monEnter(t, syncOn))
					return; // park; the whole invoke retries on wake
			}
			pushGuestFrame(t, f, declaring(owner, m), m, r, pc, oplen, true, syncOn);
			return;
		}

		// virtual/interface
		if (recv == null)
			throw new VmException(new NullPointerException());
		if (!(recv instanceof VmObject)) {
			if (!owner.host) {
				// Declared owner is a guest class, so the slot was verifier-typed
				// Lowner; yet it holds a non-guest value — always a VM bug worth
				// seeing raw (bridge.call would just report "host class missing").
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < f.locals.length; i++) {
					Object lv = f.locals[i];
					sb.append(' ').append(i).append('=')
							.append(lv instanceof VmObject
									? "VmObject:" + ((VmObject) lv).clazz.name
									: lv == null ? "-" : lv.getClass().getName());
				}
				StringBuilder st = new StringBuilder();
				for (int i = 0; i < f.sp; i++) {
					Object sv = f.stack[i];
					st.append(' ').append(i).append('=')
							.append(sv instanceof VmObject
									? "VmObject:" + ((VmObject) sv).clazz.name
									: sv == null ? "-" : sv.getClass().getName()
											+ "(" + sv + ")");
				}
				StringBuilder chain = new StringBuilder();
				for (Frame cf : t.frames) {
					chain.append("\n  ").append(cf.describe())
							.append(" pc=").append(cf.pc).append(" locals:");
					for (int i = 0; i < cf.locals.length && i < 12; i++) {
						Object lv = cf.locals[i];
						chain.append(' ').append(i).append('=')
								.append(lv instanceof VmObject
										? "VmObject:" + ((VmObject) lv).clazz.name
										: lv == null ? "-" : lv.getClass().getName()
												+ "(" + lv + ")");
					}
				}
				System.err.println("SnapVM badrecv " + r.owner + "." + r.name + r.desc
						+ " recv=" + recv.getClass().getName() + "(" + recv + ")"
						+ " pc=" + pc + " sp=" + f.sp
						+ " locals:" + sb
						+ " stack:" + st
						+ chain);
			}
			hostInvoke(vm, t, f, r, pc, oplen, 0);
			return;
		}
		VmClass rc = ((VmObject) recv).clazz;
		// monomorphic inline cache: virtual sites in game loops almost always
		// see one receiver class — skip the merged-table lookup on repeat hits
		ClassFile.Method m;
		VmClass decl;
		if (rc == r.cacheRecv) {
			m = r.cacheMethod;
			decl = r.cacheDecl;
		} else {
			m = rc.findMethod(r.key());
			decl = m == null ? null : declaring(rc, m);
			r.cacheRecv = rc;
			r.cacheMethod = m;
			r.cacheDecl = decl;
			r.cacheFast = m == null ? null
					: vm.fastCall(decl.name, m.name, m.desc);
		}
		if (m != null && m.code == null)
			throw new VmException(new AbstractMethodError(rc.name + "." + r.name));
		if (m == null) {
			// falls back to a host method (superclass boundary or Object.*) not overridden
			VmClass hb = rc.hostBoundary();
			Object[] args = popToArray(f, r, true);
			Object ret = vm.bridge.call(t, hb != null ? hb.name : "java/lang/Object",
					r.name, r.desc, args, 0);
			f.pc = pc + oplen;
			pushRet(f, r, ret);
			return;
		}
		if (r.cacheFast != null) {
			// host fast-path for a resolved guest method (registered per
			// declaring-class key — guest overrides resolve to their own
			// decl and bypass this). Monitor semantics identical to running
			// the frame: acquire before args are consumed so a failed
			// monEnter can retry the whole invoke on wake.
			Object fastSync = null;
			if ((m.access & 0x0020) != 0) { // ACC_SYNCHRONIZED: lock the receiver
				fastSync = recv;
				if (!vm.monEnter(t, fastSync))
					return; // park; the whole invoke retries on wake
			}
			Object[] args = popToArray(f, r, true);
			Object ret;
			try {
				ret = Vm.fromHost(r.cacheFast.invoke(args), !r.retRef());
			} catch (VmError | VmException e) {
				throw e;
			} catch (Exception e) {
				throw new VmException(e);
			} finally {
				if (fastSync != null)
					vm.monExit(t, fastSync);
			}
			f.pc = pc + oplen;
			pushRet(f, r, ret);
			return;
		}
		Object syncOn = null;
		if ((m.access & 0x0020) != 0) { // ACC_SYNCHRONIZED: lock the receiver
			syncOn = recv;
			if (!vm.monEnter(t, syncOn))
				return; // park; the whole invoke retries on wake
		}
		pushGuestFrame(t, f, decl, m, r, pc, oplen, true, syncOn);
	}

	private static VmClass declaring(VmClass start, ClassFile.Method m) {
		for (VmClass c = start; c != null && !c.host; c = c.superClass)
			if (c.declaredMethods.containsValue(m))
				return c;
		return start;
	}

	private static void pushRet(Frame f, ClassFile.Ref r, Object ret) {
		int k = r.retKind();
		if (k != 0) {
			f.stack[f.sp++] = ret;
			if (k == 2)
				f.stack[f.sp++] = Frame.WIDE;
		}
	}

	private static void pushGuestFrame(VmThread t, Frame caller, VmClass decl,
			ClassFile.Method m, ClassFile.Ref r, int pc, int oplen, boolean hasThis,
			Object syncOn) {
		// arg slot layout is identical on the stack and in locals
		// (wide args are value+WIDE in both) — copy the slice directly
		int slots = r.argSlots() + (hasThis ? 1 : 0);
		caller.sp -= slots;
		caller.pc = pc + oplen;
		caller.callPc = pc;
		Frame nf = t.vm.allocFrame(decl, m);
		nf.fromCall = true;
		nf.syncOn = syncOn;
		System.arraycopy(caller.stack, caller.sp, nf.locals, 0, slots);
		for (int i = 0; i < slots; i++) {
			Object v = nf.locals[i];
			// unwrap only shadows — real int-family wrappers are legitimate
			// values in reference-typed arg slots since fromHost keeps them
			if (v instanceof GuestShadow)
				nf.locals[i] = Vm.fromHost(v);
		}
		t.frames.add(nf);
	}

	/** Pop the top frame, releasing its method-level monitor if it held one. */
	private static void popFrame(VmThread t, Frame f) {
		t.frames.remove(t.frames.size() - 1);
		if (f.syncOn != null)
			t.vm.monExit(t, f.syncOn);
		finishClinitIfNeeded(f);
		t.vm.freeFrame(f);
	}

	private static Object[] popToArray(Frame f, ClassFile.Ref r, boolean hasThis) {
		int n = r.argCount() + (hasThis ? 1 : 0);
		Object[] a = new Object[n];
		for (int i = n - 1; i >= 0; i--) {
			Object v = f.stack[--f.sp];
			if (v == Frame.WIDE)
				v = f.stack[--f.sp];
			a[i] = v;
		}
		return a;
	}

	/** popToArray into the caller frame's reusable scratch — safe only for
	 *  synchronous consumers (resolved fast calls); anything that can park or
	 *  stash the array must take a private copy instead. */
	private static Object[] popToScratch(Frame f, ClassFile.Ref r, boolean hasThis) {
		int n = r.argCount() + (hasThis ? 1 : 0);
		Object[] a = f.scratch;
		if (a == null || a.length < n)
			f.scratch = a = new Object[n];
		for (int i = n - 1; i >= 0; i--) {
			Object v = f.stack[--f.sp];
			if (v == Frame.WIDE)
				v = f.stack[--f.sp];
			a[i] = v;
		}
		return a;
	}

	/**
	 * Host-class invoke with a per-Ref cached fast path. The first call goes
	 * through the full {@link HostBridge#call dispatch}; {@link HostBridge#resolveFast}
	 * then caches a builtin/adapter lambda on the Ref, so later calls on this
	 * site skip the owner switches, parkedCall/fastCall key concats and map
	 * lookups — stream-heavy games sit inside per-byte read loops where that
	 * dispatch (and its string allocations) dominated both latency and GC churn.
	 */
	private static void hostInvoke(Vm vm, VmThread t, Frame f, ClassFile.Ref r,
			int pc, int oplen, int kind) {
		HostBridge.ResolvedCall hf = r.hostFast;
		boolean thisArg = kind != 1;
		if (hf != null && hf != HostBridge.NONE) {
			Object[] args = popToScratch(f, r, thisArg);
			Object ret;
			try {
				ret = hf.invoke(t, args);
			} catch (VmError | VmException e) {
				throw e;
			} catch (Throwable e) {
				throw new VmException(e);
			}
			if (ret != HostBridge.BuiltinMarker.NOT_HANDLED) {
				f.pc = pc + oplen;
				pushRet(f, r, Vm.fromHost(ret, !r.retRef()));
				return;
			}
			// shape the resolved path doesn't cover (Object.* semantics, a
			// receiver subtype outside the builtin's cast...) — full dispatch;
			// private copy since callParked may outlive this call while a later
			// invoke is already reusing the scratch array
			ret = vm.bridge.call(t, r.owner, r.name, r.desc,
					java.util.Arrays.copyOf(args, r.argCount() + (thisArg ? 1 : 0)), kind);
			f.pc = pc + oplen;
			pushRet(f, r, ret);
			return;
		}
		Object[] args = popToArray(f, r, thisArg);
		Object ret = vm.bridge.call(t, r.owner, r.name, r.desc, args, kind);
		if (hf == null)
			r.hostFast = vm.bridge.resolveFast(r.owner, r.name, r.desc, kind);
		f.pc = pc + oplen;
		pushRet(f, r, ret);
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
		VmClass owner = r.ownerClass;
		if (owner == null)
			r.ownerClass = owner = vm.resolveClass(r.owner);
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
		VmClass decl = r.staticOwner;
		int slot = r.staticSlot;
		if (decl == null) {
			decl = owner.staticFieldOwner(r.name, r.desc);
			if (decl == null)
				throw new VmException(new NoSuchFieldError(r.owner + "." + r.name));
			slot = decl.ownStaticSlot(r.name, r.desc);
			r.staticOwner = decl;
			r.staticSlot = slot;
		}
		if (isPut) {
			Object v = f.stack[--f.sp];
			if (v == Frame.WIDE)
				v = f.stack[--f.sp];
			decl.statics[slot] = v;
			if (r.watchA == 0)
				r.watchA = (byte) (decl.name.equals("e")
						? r.name.equals("aL") ? 1 : r.name.equals("N") ? 3 : 2 : 2);
			if (r.watchA == 1)
				vm.watchS = (short[]) v;
			else if (r.watchA == 3)
				vm.watchN = (short[]) v;
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
				((VmObject) recv).fields[guestSlot(vm, r)] = v;
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
			v = ((VmObject) recv).fields[guestSlot(vm, r)];
		} else {
			v = vm.bridge.getField(recv, r);
		}
		f.stack[f.sp++] = v;
		if (VmClass.isWide(r.desc))
			f.stack[f.sp++] = Frame.WIDE;
		f.pc = pc + 3;
	}

	/** flattened field slot for a guest-class field ref, cached on the Ref. */
	private static int guestSlot(Vm vm, ClassFile.Ref r) {
		int slot = r.fieldSlot;
		if (slot < 0) {
			VmClass owner = r.ownerClass;
			if (owner == null)
				r.ownerClass = owner = vm.resolveClass(r.owner);
			Integer s = owner.fieldSlot(r.name, r.desc);
			if (s == null)
				throw new VmException(new NoSuchFieldError(r.name));
			r.fieldSlot = slot = s;
		}
		return slot;
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
	public static volatile boolean TRACE_THROWS = false;
	public static volatile String TRACE_CALLS = null;
	public static volatile String TRACE_ARGS = null;
	private static volatile java.util.regex.Pattern traceCallsPattern;
	private static volatile java.util.regex.Pattern traceArgsPattern;
	private static final java.util.Set<String> TRACE_SEEN =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static boolean traceMatches(String sig) {
		String rx = TRACE_CALLS;
		if (rx == null)
			return false;
		if (traceCallsPattern == null || !traceCallsPattern.pattern().equals(rx))
			traceCallsPattern = java.util.regex.Pattern.compile(rx);
		return traceCallsPattern.matcher(sig).matches();
	}

	private static boolean traceArgsMatches(String sig) {
		String rx = TRACE_ARGS;
		if (rx == null)
			return false;
		if (traceArgsPattern == null || !traceArgsPattern.pattern().equals(rx))
			traceArgsPattern = java.util.regex.Pattern.compile(rx);
		return traceArgsPattern.matcher(sig).matches();
	}

	// One frame spending this many TOTAL ops at top-of-stack is a runaway
	// guest loop (the 时空旅者 aL-cycle wedge spins in the draw-list walk —
	// legit frames return in <~1M ops). Tracked per-Frame, not per-step,
	// because round-robin slices reset step() counters mid-wedge.
	// Non-final: tests lower it to verify the unwind without burning 500M ops.
	static volatile int RUNAWAY_OPS = 500_000_000;

	private static int step(Vm vm, VmThread t, Frame f, int remaining, int depth) {
		byte[] code = f.method.code.code;
		ClassFile cf = f.owner.cf;
		int pc = f.pc;
		Object[] st = f.stack, lo = f.locals;
		int sp = f.sp;
		int used = 0;
		for (;;) {
		if (used >= remaining) {
			f.pc = pc; f.sp = sp;
			return used;
		}
		// Unbounded slices (EQ runUntilDone): the frame-accounted check in
		// execute only runs after step returns, which a wedge never does —
		// so guard inside the dispatch loop too. used can't reach 2^31
		// before this fires, so int overflow can't skip it.
		if (used > RUNAWAY_OPS)
			throw new VmException(new InternalError(
					"runaway frame " + f.describe()
							+ " — guest loop " + used + " ops"));
		int op = code[pc] & 0xff;
		used++;
		f.pc = pc; // exception paths + helpers read the current insn pc
		f.sp = sp; // keep frame in sync for helpers that use f.sp directly
		if (TRACE)
			System.err.printf("%-24s pc=%-4d op=%02x sp=%d stack=%s%n",
					f.owner.name + "." + f.method.name, pc, op, sp,
					java.util.Arrays.toString(java.util.Arrays.copyOf(st, Math.min(sp, 8))));

		boolean frameChanged = false;
		switch (op) {
			// const
			case 0x00: pc++; continue;
			case 0x01: st[sp++] = null; break;
			case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07: case 0x08:
				st[sp++] = op - 0x03; break;
			case 0x09: case 0x0a:
				st[sp++] = (long) (op - 0x09); st[sp++] = Frame.WIDE; break;
			case 0x0b: case 0x0c: case 0x0d:
				st[sp++] = (float) (op - 0x0b); break;
			case 0x0e: case 0x0f:
				st[sp++] = (double) (op - 0x0e); st[sp++] = Frame.WIDE; break;
			case 0x10: st[sp++] = (int) code[pc + 1]; pc += 2; continue;
			case 0x11: st[sp++] = s2(code, pc + 1); pc += 3; continue;
			case 0x12: f.sp = sp; ldc(vm, f, cf, code[pc + 1] & 0xff); sp = f.sp; pc += 2; continue;
			case 0x13: f.sp = sp; ldc(vm, f, cf, u2(code, pc + 1)); sp = f.sp; pc += 3; continue;
			case 0x14:
				st[sp++] = cf.cpValue(u2(code, pc + 1)); st[sp++] = Frame.WIDE;
				pc += 3; continue;
			// xload n
			case 0x15: case 0x17: case 0x19:
				st[sp++] = lo[code[pc + 1] & 0xff]; pc += 2; continue;
			case 0x16: case 0x18:
				st[sp++] = lo[code[pc + 1] & 0xff]; st[sp++] = Frame.WIDE;
				pc += 2; continue;
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
			// array loads — typed fast paths; reflective fallback keeps exotic cases
			case 0x2e: { // iaload
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = arr instanceof int[] ? ((int[]) arr)[idx]
						: Vm.fromHost(Array.get(arr, idx));
				break;
			}
			case 0x30: { // faload
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = arr instanceof float[] ? ((float[]) arr)[idx]
						: Vm.fromHost(Array.get(arr, idx));
				break;
			}
			case 0x32: { // aaload
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = arr instanceof Object[] ? ((Object[]) arr)[idx]
						: Vm.fromHost(Array.get(arr, idx));
				break;
			}
			case 0x33: { // baload — byte[] or boolean[], stack convention is Integer
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = arr instanceof byte[] ? (int) ((byte[]) arr)[idx]
						: arr instanceof boolean[] ? (((boolean[]) arr)[idx] ? 1 : 0)
						: Vm.fromHost(Array.get(arr, idx));
				break;
			}
			case 0x34: { // caload
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = arr instanceof char[] ? (int) ((char[]) arr)[idx]
						: Vm.fromHost(Array.get(arr, idx));
				break;
			}
			case 0x35: { // saload
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr instanceof short[]) {
					int sv = (int) ((short[]) arr)[idx];
					if (arr == vm.watchN && vm.watchN != null)
						vm.nProbe(idx, sv);
					st[sp++] = sv;
				} else
					st[sp++] = Vm.fromHost(Array.get(arr, idx));
				break;
			}
			case 0x2f: { // laload
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = arr instanceof long[] ? ((long[]) arr)[idx]
						: Array.get(arr, idx);
				st[sp++] = Frame.WIDE;
				break;
			}
			case 0x31: { // daload
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				st[sp++] = arr instanceof double[] ? ((double[]) arr)[idx]
						: Array.get(arr, idx);
				st[sp++] = Frame.WIDE;
				break;
			}
			// xstore n
			case 0x36: case 0x38: case 0x3a: {
				int n = code[pc + 1] & 0xff;
				lo[n] = st[--sp];
				pc += 2; continue;
			}
			case 0x37: case 0x39: {
				int n = code[pc + 1] & 0xff;
				lo[n + 1] = Frame.WIDE;
				lo[n] = st[sp - 2];
				sp -= 2;
				pc += 2; continue;
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
			// array stores — typed fast paths, narrowing/reflective fallback preserved
			case 0x4f: { // iastore
				Object v = st[--sp];
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr instanceof int[] && v instanceof Integer)
					((int[]) arr)[idx] = (Integer) v;
				else
					storeArray(arr, idx, v);
				break;
			}
			case 0x51: { // fastore
				Object v = st[--sp];
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr instanceof float[] && v instanceof Float)
					((float[]) arr)[idx] = (Float) v;
				else
					storeArray(arr, idx, v);
				break;
			}
			case 0x54: { // bastore — byte[] or boolean[]
				Object v = st[--sp];
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr instanceof byte[] && v instanceof Integer)
					((byte[]) arr)[idx] = (byte) (int) (Integer) v;
				else if (arr instanceof boolean[] && v instanceof Integer)
					((boolean[]) arr)[idx] = ((Integer) v & 1) != 0;
				else
					storeArray(arr, idx, v);
				break;
			}
			case 0x55: { // castore
				Object v = st[--sp];
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr instanceof char[] && v instanceof Integer)
					((char[]) arr)[idx] = (char) (int) (Integer) v;
				else
					storeArray(arr, idx, v);
				break;
			}
			case 0x56: { // sastore
				Object v = st[--sp];
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr instanceof short[] && v instanceof Integer) {
					((short[]) arr)[idx] = (short) (int) (Integer) v;
					if (arr == vm.watchS && vm.watchS != null)
						vm.watchProbe(t, f, pc, idx, (Integer) v);
				} else
					storeArray(arr, idx, v);
				break;
			}
			case 0x50: { // lastore
				Object v = st[sp - 2];
				sp -= 2;
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr instanceof long[] && v instanceof Long)
					((long[]) arr)[idx] = (Long) v;
				else
					storeArray(arr, idx, v);
				break;
			}
			case 0x52: { // dastore
				Object v = st[sp - 2];
				sp -= 2;
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr instanceof double[] && v instanceof Double)
					((double[]) arr)[idx] = (Double) v;
				else
					storeArray(arr, idx, v);
				break;
			}
			case 0x53: { // aastore — the only store needing a covariance check
				Object v = st[--sp];
				int idx = (Integer) st[--sp];
				Object arr = st[--sp];
				if (arr.getClass() == Object[].class && vm.guestArrayTypes.get(arr) == null)
					((Object[]) arr)[idx] = v; // plain Object[]: no constraint
				else
					storeArrayChecked(vm, arr, idx, v);
				break;
			}
			// stack ops
			case 0x57: sp--; break;
			case 0x58: sp -= 2; break; // pop2: wide pair or two singles = 2 slots
			case 0x59: st[sp] = st[sp - 1]; sp++; break;             // dup
			case 0x5a: {                                             // dup_x1
				Object b = st[sp - 1], a = st[sp - 2];
				st[sp - 2] = b; st[sp - 1] = a; st[sp++] = b;
				break;
			}
			case 0x5b: sp = dupX2(st, sp); break;                     // dup_x2
			case 0x5c:                                               // dup2
				if (st[sp - 1] == Frame.WIDE) {
					st[sp] = st[sp - 2]; st[sp + 1] = Frame.WIDE; sp += 2;
				} else {
					st[sp] = st[sp - 2]; st[sp + 1] = st[sp - 1]; sp += 2;
				}
				break;
			case 0x5d: sp = dup2X1(st, sp); break;                    // dup2_x1
			case 0x5e: sp = dup2X2(st, sp); break;                    // dup2_x2
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
			case 0x79: st[sp - 3] = (Long) st[sp - 3] << (Integer) st[sp - 1]; sp--; break;
			case 0x7a: st[sp - 2] = (Integer) st[sp - 2] >> (Integer) st[sp - 1]; sp--; break;
			case 0x7b: st[sp - 3] = (Long) st[sp - 3] >> (Integer) st[sp - 1]; sp--; break;
			case 0x7c: st[sp - 2] = (Integer) st[sp - 2] >>> (Integer) st[sp - 1]; sp--; break;
			case 0x7d: st[sp - 3] = (Long) st[sp - 3] >>> (Integer) st[sp - 1]; sp--; break;
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
				pc += 3; continue;
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
				pc = go ? pc + s2(code, pc + 1) : pc + 3;
				continue;
			}
			case 0x9f: case 0xa0: case 0xa1: case 0xa2: case 0xa3: case 0xa4: {
				int b = (Integer) st[--sp], a = (Integer) st[--sp];
				boolean go = op == 0x9f ? a == b : op == 0xa0 ? a != b : op == 0xa1 ? a < b
						: op == 0xa2 ? a >= b : op == 0xa3 ? a > b : a <= b;
				pc = go ? pc + s2(code, pc + 1) : pc + 3;
				continue;
			}
			case 0xa5: case 0xa6: {
				Object b = st[--sp], a = st[--sp];
				boolean go = (op == 0xa5) == (a == b);
				pc = go ? pc + s2(code, pc + 1) : pc + 3;
				continue;
			}
			case 0xa7: pc += s2(code, pc + 1); continue;
			case 0xa8: case 0xa9:                                      // jsr/ret
				throw new VmError("jsr/ret not supported");
			case 0xaa: // tableswitch
				pc += tableSwitchOff(code, pc, (Integer) st[--sp]);
				continue;
			case 0xab: // lookupswitch
				pc += lookupSwitchOff(code, pc, (Integer) st[--sp]);
				continue;
			// returns
			case 0xac: case 0xae: case 0xb0: // ireturn/freturn/areturn
				f.sp = sp; doReturnTyped(t, f); frameChanged = true; break;
			case 0xad: case 0xaf: // lreturn/dreturn
				f.sp = sp; doReturnWide(t, f); frameChanged = true; break;
			case 0xb1:
				f.sp = sp; doReturnVoid(t); frameChanged = true; break;
			// field access
			case 0xb2: case 0xb3: {
				f.sp = sp;
				getPutStatic(vm, t, f, ref(cf, u2(code, pc + 1)), pc, op == 0xb3);
				if (t.top() != f || t.state != VmThread.RUNNABLE) {
					frameChanged = true; // clinit pushed or parked — reload
					break;
				}
				pc = f.pc; sp = f.sp; continue;
			}
			case 0xb4: case 0xb5: {
				f.sp = sp;
				getPutField(vm, t, f, ref(cf, u2(code, pc + 1)), pc, op == 0xb5);
				pc = f.pc; sp = f.sp; continue;
			}
			// invokes
			case 0xb6: case 0xb7: case 0xb8: case 0xb9: {
				f.sp = sp;
				invoke(vm, t, f, ref(cf, u2(code, pc + 1)), pc,
						op == 0xb9 ? 5 : 3,
						op == 0xb7 ? 2 : op == 0xb8 ? 1 : 0);
				frameChanged = true;
				break;
			}
			case 0xba: throw new VmError("invokedynamic not supported");
			// new/arrays
			case 0xbb:
				f.sp = sp;
				if (!newOp(vm, t, f, cf, u2(code, pc + 1), pc)) {
					frameChanged = true; // clinit pushed or parked — reload
					break;
				}
				pc = f.pc; sp = f.sp; continue;
			case 0xbc: { // newarray
				int n = (Integer) st[--sp];
				int at = code[pc + 1] & 0xff;
				st[sp++] = newPrimArray(at, n);
				pc += 2;
				continue;
			}
			case 0xbd: { // anewarray
				int n = (Integer) st[--sp];
				VmClass c = vm.resolveClass(cf.className(u2(code, pc + 1)));
				st[sp++] = newObjArray(vm, c, n);
				pc += 3;
				continue;
			}
			case 0xbe: { // arraylength
				Object arr = st[--sp];
				if (arr == null)
					throw new VmException(new NullPointerException());
				st[sp++] = arr instanceof Object[] ? ((Object[]) arr).length
						: arr instanceof byte[] ? ((byte[]) arr).length
						: arr instanceof int[] ? ((int[]) arr).length
						: arr instanceof char[] ? ((char[]) arr).length
						: arr instanceof short[] ? ((short[]) arr).length
						: arr instanceof long[] ? ((long[]) arr).length
						: arr instanceof float[] ? ((float[]) arr).length
						: arr instanceof double[] ? ((double[]) arr).length
						: arr instanceof boolean[] ? ((boolean[]) arr).length
						: Array.getLength(arr);
				break;
			}
			case 0xbf: { // athrow
				Object ex = st[--sp];
				if (ex == null)
					ex = new NullPointerException();
				throw new VmException(ex);
			}
			case 0xc0: // checkcast
				checkCast(vm, cf, u2(code, pc + 1), st[sp - 1]);
				pc += 3; continue;
			case 0xc1: { // instanceof
				Object o = st[sp - 1];
				st[sp - 1] = o != null
						&& vm.resolveClass(cf.className(u2(code, pc + 1))).isInstance(o) ? 1 : 0;
				pc += 3; continue;
			}
			case 0xc2: { // monitorenter
				Object o = st[sp - 1];
				if (o == null)
					throw new VmException(new NullPointerException());
				if (!vm.monEnter(t, o)) {
					f.sp = sp; // keep the ref on stack; retry instruction on wake
					return used;
				}
				sp--;
				break;
			}
			case 0xc3: { // monitorexit
				Object o = st[--sp];
				if (o == null)
					throw new VmException(new NullPointerException());
				vm.monExit(t, o);
				break;
			}
			case 0xc4: f.sp = sp; pc = wideOp(code, pc, st, lo, f);
				sp = f.sp; continue; // wide
			case 0xc5: f.sp = sp; multiNewArray(vm, cf, code, pc, f);
				sp = f.sp; pc = f.pc; continue; // multianewarray
			case 0xc6: { // ifnull
				Object o = st[--sp];
				pc = o == null ? pc + s2(code, pc + 1) : pc + 3;
				continue;
			}
			case 0xc7: { // ifnonnull
				Object o = st[--sp];
				pc = o != null ? pc + s2(code, pc + 1) : pc + 3;
				continue;
			}
			case 0xc8: pc += s4(code, pc + 1); continue;  // goto_w
			case 0xc9: throw new VmError("jsr_w not supported");
			default:
				throw new VmError("unimplemented opcode 0x" + Integer.toHexString(op)
						+ " in " + f.describe());
		}
		if (frameChanged) {
			// invoke pushed a frame / return popped / clinit pushed / parked —
			// reload locals from the (possibly different) top frame inline
			if (t.frames.isEmpty() || t.state != VmThread.RUNNABLE
					|| (depth >= 0 && t.frames.size() <= depth))
				return used;
			f = t.top();
			code = f.method.code.code;
			cf = f.owner.cf;
			st = f.stack; lo = f.locals;
			sp = f.sp; pc = f.pc;
			continue;
		}
		pc++; continue;
		}
	}

	private static ClassFile.Ref ref(ClassFile cf, int idx) {
		return (ClassFile.Ref) cf.cpValue(idx);
	}

	// ---------- cold-op helpers (extracted to keep step() under the 8K JIT limit) ----------

	private static int dupX2(Object[] st, int sp) {
		if (st[sp - 2] == Frame.WIDE) {
			Object b = st[sp - 1], v = st[sp - 3];
			st[sp - 3] = b; st[sp - 2] = v; st[sp - 1] = Frame.WIDE; st[sp++] = b;
		} else {
			Object c = st[sp - 1], b = st[sp - 2], a = st[sp - 3];
			st[sp - 3] = c; st[sp - 2] = a; st[sp - 1] = b; st[sp++] = c;
		}
		return sp;
	}

	private static int dup2X1(Object[] st, int sp) {
		if (st[sp - 1] == Frame.WIDE) { // wide over single
			Object v = st[sp - 2], a = st[sp - 3];
			st[sp - 3] = v; st[sp - 2] = Frame.WIDE; st[sp - 1] = a;
			st[sp] = v; st[sp + 1] = Frame.WIDE; sp += 2;
		} else {
			Object c = st[sp - 1], b = st[sp - 2], a = st[sp - 3];
			st[sp - 3] = b; st[sp - 2] = c; st[sp - 1] = a;
			st[sp] = b; st[sp + 1] = c; sp += 2;
		}
		return sp;
	}

	private static int dup2X2(Object[] st, int sp) {
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
			// [.., w, WIDE, a, b] -> [.., a, b, w, WIDE, a, b]
			Object b = st[sp - 1], a = st[sp - 2], w = st[sp - 4];
			st[sp - 4] = a; st[sp - 3] = b;
			st[sp - 2] = w; st[sp - 1] = Frame.WIDE;
			st[sp] = a; st[sp + 1] = b; sp += 2;
		} else { // two singles over two singles
			Object d = st[sp - 1], c = st[sp - 2], b = st[sp - 3], a = st[sp - 4];
			st[sp - 4] = c; st[sp - 3] = d; st[sp - 2] = a; st[sp - 1] = b;
			st[sp] = c; st[sp + 1] = d; sp += 2;
		}
		return sp;
	}

	private static int tableSwitchOff(byte[] code, int pc, int idx) {
		int base = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
		int def = s4(code, base);
		int low = s4(code, base + 4), high = s4(code, base + 8);
		return (idx >= low && idx <= high)
				? s4(code, base + 12 + (idx - low) * 4) : def;
	}

	private static int lookupSwitchOff(byte[] code, int pc, int key) {
		int base = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
		int def = s4(code, base);
		int npairs = s4(code, base + 4);
		for (int i = 0; i < npairs; i++)
			if (s4(code, base + 8 + i * 8) == key)
				return s4(code, base + 12 + i * 8);
		return def;
	}

	/** wide-prefixed op; reads/updates f.sp, returns the new pc. */
	private static int wideOp(byte[] code, int pc, Object[] st, Object[] lo, Frame f) {
		int wop = code[pc + 1] & 0xff;
		int n = u2(code, pc + 2);
		int sp = f.sp;
		if (wop == 0x84) {
			lo[n] = (Integer) lo[n] + s2(code, pc + 4);
			return pc + 6;
		}
		switch (wop) { // wide load/store
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
		f.sp = sp;
		return pc + 4;
	}

	private static void checkCast(Vm vm, ClassFile cf, int idx, Object o) {
		if (o != null) {
			VmClass c = vm.resolveClass(cf.className(idx));
			if (!c.isInstance(o))
				throw new VmException(new ClassCastException(
						o.getClass().getName() + " -> " + c.name));
		}
	}

	/** new: pushes the object (or NewMarker) and advances f.pc; false if a clinit
	 *  frame was pushed or the thread parked — caller must reload. */
	private static boolean newOp(Vm vm, VmThread t, Frame f, ClassFile cf, int idx, int pc) {
		VmClass c = vm.resolveClass(cf.className(idx));
		if (c.host) {
			f.stack[f.sp++] = new HostBridge.NewMarker(c);
		} else {
			if (!vm.ensureInit(c, t))
				return false; // clinit frame pushed / parked — retry this insn
			f.stack[f.sp++] = new VmObject(c);
		}
		f.pc = pc + 3;
		return true;
	}

	private static void multiNewArray(Vm vm, ClassFile cf, byte[] code, int pc, Frame f) {
		int dims = code[pc + 3] & 0xff;
		VmClass c = vm.resolveClass(cf.className(u2(code, pc + 1)));
		int[] sizes = new int[dims];
		for (int i = dims - 1; i >= 0; i--)
			sizes[i] = (Integer) f.stack[--f.sp];
		f.stack[f.sp++] = multiArray(vm, c.name, sizes, 0);
		f.pc = pc + 4;
	}

	private static void doReturnTyped(VmThread t, Frame f) {
		Object ret = f.stack[--f.sp];
		popFrame(t, f);
		if (t.frames.isEmpty()) {
			t.result = ret;
			t.terminate();
			return;
		}
		t.nestedResult = ret;
		if (!f.external) {
			Frame caller = t.top();
			caller.stack[caller.sp++] = ret;
		}
	}

	private static void doReturnWide(VmThread t, Frame f) {
		Object ret = f.stack[f.sp - 2];
		popFrame(t, f);
		if (t.frames.isEmpty()) {
			t.result = ret;
			t.terminate();
			return;
		}
		t.nestedResult = ret;
		if (!f.external) {
			Frame caller = t.top();
			caller.stack[caller.sp++] = ret;
			caller.stack[caller.sp++] = Frame.WIDE;
		}
	}

	private static void doReturnVoid(VmThread t) {
		Frame f = t.top();
		popFrame(t, f);
		if (t.frames.isEmpty()) {
			t.terminate();
			return;
		}
		t.nestedResult = null;
	}

	private static void storeArray(Object arr, int idx, Object v) {
		if (arr == null)
			throw new VmException(new NullPointerException());
		// Guest Z/B/S/C values are Integer on the operand stack; JVM xstore
		// truncates them, but Array.set only unwraps exact/widening types.
		if (v instanceof Integer) {
			int iv = (Integer) v;
			if (arr instanceof byte[]) {
				((byte[]) arr)[idx] = (byte) iv;
				return;
			}
			if (arr instanceof boolean[]) {
				((boolean[]) arr)[idx] = (iv & 1) != 0;
				return;
			}
			if (arr instanceof char[]) {
				((char[]) arr)[idx] = (char) iv;
				return;
			}
			if (arr instanceof short[]) {
				((short[]) arr)[idx] = (short) iv;
				return;
			}
		}
		try {
			Array.set(arr, idx, v);
		} catch (IllegalArgumentException e) {
			throw new VmException(new ArrayStoreException());
		}
	}

	private static void storeArrayChecked(Vm vm, Object arr, int idx, Object v) {
		if (arr == null)
			throw new VmException(new NullPointerException());
		// guest-side arrays are Object[] under the hood — their real component
		// type lives in guestArrayTypes; enforce covariance ourselves
		VmClass comp = vm.guestArrayTypes.get(arr);
		if (comp != null && v != null && !comp.isInstance(v))
			throw new VmException(new ArrayStoreException());
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

	/**
	 * Allocate a multi-dim array with real runtime types where possible:
	 * int[][] is a real int[][], String[][] a real String[][]. Only levels
	 * whose component is a guest class degrade to Object[], tracked in
	 * guestArrayTypes so checkcast/aastore keep JVM semantics.
	 */
	private static Object multiArray(Vm vm, String desc, int[] sizes, int d) {
		int n = sizes[d];
		String comp = desc.substring(d + 1); // component descriptor at this level
		boolean leaf = d == sizes.length - 1;
		VmClass cc = null;
		if (comp.charAt(0) == 'L')
			cc = vm.resolveClass(comp.substring(1, comp.length() - 1));
		else if (comp.charAt(0) == '[')
			cc = vm.resolveClass(comp);
		Object a;
		if (leaf && cc == null) {
			a = newPrimArray(comp.charAt(0), n);
		} else if (cc != null && cc.host) {
			a = Array.newInstance(cc.hostClass, n);
		} else {
			a = new Object[n];
			if (cc != null)
				vm.guestArrayTypes.put(a, cc);
		}
		if (!leaf) {
			Object[] oa = (Object[]) a;
			for (int i = 0; i < n; i++)
				oa[i] = multiArray(vm, desc, sizes, d + 1);
		}
		return a;
	}

	private static Object newPrimArray(char c, int n) {
		switch (c) {
			case 'I': return new int[n];
			case 'J': return new long[n];
			case 'F': return new float[n];
			case 'D': return new double[n];
			case 'B': return new byte[n];
			case 'C': return new char[n];
			case 'S': return new short[n];
			case 'Z': return new boolean[n];
			default: throw new VmError("bad element type " + c);
		}
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
