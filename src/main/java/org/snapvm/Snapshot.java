package org.snapvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot writer/reader — see docs/snapshot-format.md. Serializes guest
 * values at the logical level (never memory layout), IDs the object graph for
 * cycles/identity, and routes host objects through registered codecs.
 */
final class Snapshot {
	private Snapshot() {
	}

	// value tags
	private static final int T_NULL = 0, T_INT = 1, T_LONG = 2, T_FLOAT = 3,
			T_DOUBLE = 4, T_BOOL = 5, T_BYTE = 6, T_SHORT = 7, T_CHAR = 8,
			T_WIDE = 9, T_REF = 10, T_CLASS = 11;
	// REF kinds
	private static final int K_STR = 0, K_OBJ = 1, K_THREAD_BOX = 2,
			K_HANDLE = 3, K_THROWABLE = 4, K_PROXY = 5, K_POBJ = 6,
			K_ARR_B = 10, K_ARR_S = 11, K_ARR_C = 12, K_ARR_I = 13, K_ARR_Z = 14,
			K_ARR_J = 15, K_ARR_F = 16, K_ARR_D = 17, K_ARR_OBJ = 18,
			K_ARR_TYPED = 19;
	// waitMonitor encodings
	private static final int W_NONE = 0, W_MONITOR = 1, W_PARKED = 2, W_CLINIT = 3;

	static byte[] write(Vm vm) {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
			new W(vm, new DataOutputStream(bos)).run();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new VmError("snapshot write failed", e);
		}
	}

	static void read(Vm vm, byte[] data) {
		try {
			new R(vm, new DataInputStream(new ByteArrayInputStream(data))).run();
		} catch (IOException e) {
			throw new VmError("snapshot read failed", e);
		}
	}

	// ================= writer =================

	private static final class W {
		final Vm vm;
		final DataOutputStream out;
		final IdentityHashMap<Object, Integer> ids = new IdentityHashMap<>();
		final IdentityHashMap<VmThread, Integer> tids = new IdentityHashMap<>();
		final IdentityHashMap<Vm.Monitor, Object> monObj = new IdentityHashMap<>();
		int nextId = 1;

		W(Vm vm, DataOutputStream out) {
			this.vm = vm;
			this.out = out;
		}

		void run() throws IOException {
			out.writeBytes("SNAP");
			out.writeShort(3); // v3: per-thread serials (position-free covered detection)
			synchronized (vm.threads) {
				int ti = 1;
				for (VmThread t : vm.threads)
					// preserved stale-epoch host callbacks keep stale frames —
					// serializing them would duplicate graph objects
					if (t.epoch == vm.epoch)
						tids.put(t, ti++);
			}
			synchronized (vm.monitors) {
				for (Map.Entry<Object, Vm.Monitor> e : vm.monitors.entrySet())
					monObj.put(e.getValue(), e.getKey());
			}
			writeGlobals();
			writeClasses();
			writeThreads();
			writeMonitors();
			writeArrayTypes();
			writeBoxes();
			writeProxies();
			out.writeInt(vm.rrCursor);
		}

		void writeGlobals() throws IOException {
			out.writeInt(vm.j2meProps.size());
			for (String n : vm.j2meProps.stringPropertyNames()) {
				str(n);
				str(vm.j2meProps.getProperty(n));
			}
			out.writeInt(vm.interned.size());
			for (String s : vm.interned.keySet())
				value(s);
		}

		void writeClasses() throws IOException {
			List<VmClass> cs = new ArrayList<>();
			for (VmClass c : vm.classes.values())
				if (!c.host && c.cf != null && (c.initDone || c.initRunning))
					cs.add(c);
			out.writeInt(cs.size());
			for (VmClass c : cs) {
				str(c.name);
				out.writeBoolean(c.initDone);
				out.writeBoolean(c.initRunning);
				out.writeInt(tid(c.initThread));
				Object[] st = c.statics;
				out.writeInt(st == null ? 0 : st.length);
				if (st != null)
					for (Object v : st)
						value(v);
			}
		}

		int tid(VmThread t) {
			Integer i = t == null ? null : tids.get(t);
			return i == null ? 0 : i;
		}

		void writeThreads() throws IOException {
			int n = 0;
			synchronized (vm.threads) {
				for (VmThread t : vm.threads)
					if (tids.containsKey(t))
						n++;
			}
			out.writeInt(n);
			synchronized (vm.threads) {
				for (VmThread t : vm.threads)
					// a host thread can callFromHost→newThread mid-write —
					// it holds threads but not execLock. Threads born after
					// the id map was built are not part of the frozen state.
					if (tids.containsKey(t))
						writeThread(t);
			}
		}

		void writeThread(VmThread t) throws IOException {
			out.writeInt(tids.get(t));
			out.writeLong(t.serial);
			out.writeByte(t.state);
			out.writeLong(t.state == VmThread.TIMED_WAITING
					? Math.max(0, t.wakeTime - System.currentTimeMillis())
					: 0);
			Object wm = t.waitMonitor;
			if (wm == null) {
				out.writeByte(W_NONE);
			} else if (wm == Vm.PARKED) {
				out.writeByte(W_PARKED);
			} else if (wm instanceof VmClass) {
				out.writeByte(W_CLINIT);
				str(((VmClass) wm).name);
			} else if (wm instanceof Vm.Monitor) {
				out.writeByte(W_MONITOR);
				value(monObj.get(wm));
			} else {
				throw new VmError("unexpected waitMonitor " + wm);
			}
			out.writeInt(t.waitReleasedCount);
			out.writeBoolean(t.resumingFromWait);
			out.writeBoolean(t.interrupted);
			out.writeBoolean(t.pendingInterrupt);
			out.writeInt(t.interruptPc);
			out.writeInt(tid(t.joinTarget));
			synchronized (t.joinWaiters) {
				out.writeInt(t.joinWaiters.size());
				for (VmThread w : t.joinWaiters)
					out.writeInt(tid(w));
			}
			value(t.runTarget);
			value(t.hostThread);
			value(t.result);
			value(t.nestedResult);
			if (t.lastError == null) {
				out.writeBoolean(false);
			} else {
				out.writeBoolean(true);
				value(t.lastError.thrown);
			}
			if (t.parkedCallKey == null) {
				out.writeBoolean(false);
			} else {
				out.writeBoolean(true);
				str(t.parkedCallKey);
				Object[] pa = t.parkedArgs;
				out.writeInt(pa == null ? 0 : pa.length);
				if (pa != null)
					for (Object a : pa)
						value(a);
			}
			out.writeInt(t.frames.size());
			for (Frame f : t.frames)
				writeFrame(f);
		}

		void writeFrame(Frame f) throws IOException {
			str(f.owner.name);
			str(f.method.name);
			str(f.method.desc);
			out.writeInt(f.pc);
			out.writeInt(f.sp);
			out.writeInt(f.callPc);
			out.writeBoolean(f.fromCall);
			out.writeBoolean(f.external);
			value(f.syncOn);
			out.writeInt(f.locals.length);
			for (Object v : f.locals)
				value(v);
			for (int i = 0; i < f.sp; i++)
				value(f.stack[i]);
		}

		void writeMonitors() throws IOException {
			List<Object[]> rows = new ArrayList<>();
			synchronized (vm.monitors) {
				for (Map.Entry<Object, Vm.Monitor> e : vm.monitors.entrySet()) {
					Vm.Monitor m = e.getValue();
					synchronized (m) {
						if (m.owner == null && m.waiters.isEmpty())
							continue;
						int[] ws = new int[m.waiters.size()];
						for (int i = 0; i < ws.length; i++)
							ws[i] = tid(m.waiters.get(i));
						rows.add(new Object[]{e.getKey(), m.owner, m.count, ws});
					}
				}
			}
			out.writeInt(rows.size());
			for (Object[] r : rows) {
				value(r[0]);
				out.writeInt(tid((VmThread) r[1]));
				out.writeInt((Integer) r[2]);
				int[] ws = (int[]) r[3];
				out.writeInt(ws.length);
				for (int w : ws)
					out.writeInt(w);
			}
		}

		void writeArrayTypes() throws IOException {
			int n = 0;
			for (Map.Entry<Object, VmClass> e : vm.guestArrayTypes.entrySet())
				if (ids.containsKey(e.getKey()))
					n++;
			out.writeInt(n);
			for (Map.Entry<Object, VmClass> e : vm.guestArrayTypes.entrySet()) {
				Integer id = ids.get(e.getKey());
				if (id != null) {
					out.writeInt(id);
					str(e.getValue().name);
				}
			}
		}

		void writeBoxes() throws IOException {
			synchronized (vm.greenThreads) {
				out.writeInt(vm.greenThreads.size());
				for (Map.Entry<Object, VmThread> e : vm.greenThreads.entrySet()) {
					value(e.getKey());
					out.writeInt(tid(e.getValue()));
					value(vm.greenTargets.get(e.getKey()));
				}
			}
		}

		void writeProxies() throws IOException {
			List<Map.Entry<Object, Object>> rows;
			synchronized (vm.proxies) {
				rows = new ArrayList<>(vm.proxies.entrySet());
			}
			out.writeInt(rows.size());
			for (Map.Entry<Object, Object> e : rows) {
				Class<?>[] ifs = e.getKey().getClass().getInterfaces();
				str(ifs.length > 0 ? ifs[0].getName() : "");
				value(e.getValue());
			}
		}

		// ---- values ----

		void value(Object v) throws IOException {
			if (v == null) {
				out.writeByte(T_NULL);
				return;
			}
			if (v instanceof Integer) {
				out.writeByte(T_INT);
				out.writeInt((Integer) v);
				return;
			}
			if (v instanceof Long) {
				out.writeByte(T_LONG);
				out.writeLong((Long) v);
				return;
			}
			if (v instanceof Float) {
				out.writeByte(T_FLOAT);
				out.writeFloat((Float) v);
				return;
			}
			if (v instanceof Double) {
				out.writeByte(T_DOUBLE);
				out.writeDouble((Double) v);
				return;
			}
			if (v instanceof Boolean) {
				out.writeByte(T_BOOL);
				out.writeBoolean((Boolean) v);
				return;
			}
			if (v instanceof Byte) {
				out.writeByte(T_BYTE);
				out.writeByte((Byte) v);
				return;
			}
			if (v instanceof Short) {
				out.writeByte(T_SHORT);
				out.writeShort((Short) v);
				return;
			}
			if (v instanceof Character) {
				out.writeByte(T_CHAR);
				out.writeChar((Character) v);
				return;
			}
			if (v == Frame.WIDE) {
				out.writeByte(T_WIDE);
				return;
			}
			if (v instanceof GuestClass) {
				out.writeByte(T_CLASS);
				str(((GuestClass) v).cls.name);
				return;
			}
			if (v instanceof GuestShadow) {
				value(((GuestShadow) v).guest());
				return;
			}
			out.writeByte(T_REF);
			Integer id = ids.get(v);
			if (id != null) {
				out.writeInt(id);
				return;
			}
			id = nextId++;
			ids.put(v, id);
			out.writeInt(id);
			if (v instanceof String) {
				out.writeByte(K_STR);
				str((String) v);
			} else if (v instanceof VmObject) {
				writeObj((VmObject) v);
			} else if (v instanceof Thread) {
				out.writeByte(K_THREAD_BOX);
			} else if (v instanceof Throwable) {
				out.writeByte(K_THROWABLE);
				Throwable th = (Throwable) v;
				str(th.getClass().getName());
				str(String.valueOf(th.getMessage()));
			} else if (v.getClass().isArray()) {
				writeArray(v, v.getClass().getComponentType());
			} else if (v.getClass() == Object.class) {
				// plain java.lang.Object: pure identity token (guest lock objects)
				out.writeByte(K_POBJ);
			} else if (proxyTarget(v) != null) {
				// a host proxy object in guest state unwraps to its guest
				out.writeByte(K_PROXY);
				value(proxyTarget(v));
			} else {
				HostHandle h = findHandle(v.getClass());
				if (h == null)
					throw new VmError("cannot snapshot host object "
							+ v.getClass().getName()
							+ " — register a HostHandle codec");
				out.writeByte(K_HANDLE);
				str(v.getClass().getName());
				value(h.capture(vm, v));
			}
		}

		Object proxyTarget(Object v) {
			synchronized (vm.proxies) {
				return vm.proxies.get(v);
			}
		}

		HostHandle findHandle(Class<?> c) {
			for (Class<?> x = c; x != null; x = x.getSuperclass()) {
				HostHandle h = vm.hostHandles.get(x);
				if (h != null)
					return h;
				for (Class<?> i : x.getInterfaces()) {
					h = vm.hostHandles.get(i);
					if (h != null)
						return h;
				}
			}
			return null;
		}

		void writeObj(VmObject o) throws IOException {
			out.writeByte(K_OBJ);
			str(o.clazz.name);
			out.writeLong(o.serial); // 0 unless this obj was proxied to host code
			out.writeInt(o.fields.length);
			for (Object f : o.fields)
				value(f);
			if (o.hostShadow == null) {
				out.writeBoolean(false);
			} else {
				out.writeBoolean(true);
				VmClass hb = o.clazz.hostBoundary();
				str(hb.name);
				ShadowCodec c = vm.shadowCodecs.get(hb.hostClass);
				if (c == null)
					throw new VmError("hostShadow " + hb.name
							+ " has no ShadowCodec — cannot snapshot");
				value(c.capture(vm, o, o.hostShadow));
			}
			value(o.superCtorArgs);
			value(o.exceptionCause);
			out.writeBoolean(o.exceptionTrace != null);
			if (o.exceptionTrace != null)
				str(o.exceptionTrace);
		}

		void writeArray(Object arr, Class<?> comp) throws IOException {
			int n = Array.getLength(arr);
			if (comp == byte.class) {
				out.writeByte(K_ARR_B);
				out.writeInt(n);
				out.write((byte[]) arr);
			} else if (comp == boolean.class) {
				out.writeByte(K_ARR_Z);
				out.writeInt(n);
				for (boolean x : (boolean[]) arr)
					out.writeBoolean(x);
			} else if (comp == short.class) {
				out.writeByte(K_ARR_S);
				out.writeInt(n);
				for (short x : (short[]) arr)
					out.writeShort(x);
			} else if (comp == char.class) {
				out.writeByte(K_ARR_C);
				out.writeInt(n);
				for (char x : (char[]) arr)
					out.writeChar(x);
			} else if (comp == int.class) {
				out.writeByte(K_ARR_I);
				out.writeInt(n);
				for (int x : (int[]) arr)
					out.writeInt(x);
			} else if (comp == long.class) {
				out.writeByte(K_ARR_J);
				out.writeInt(n);
				for (long x : (long[]) arr)
					out.writeLong(x);
			} else if (comp == float.class) {
				out.writeByte(K_ARR_F);
				out.writeInt(n);
				for (float x : (float[]) arr)
					out.writeFloat(x);
			} else if (comp == double.class) {
				out.writeByte(K_ARR_D);
				out.writeInt(n);
				for (double x : (double[]) arr)
					out.writeDouble(x);
			} else {
				// TYPED carries the runtime component class name so restore
				// rebuilds e.g. a real int[][] instead of a bare Object[] —
				// guest checkcast [[I would fail on a revived Object[].
				out.writeByte(K_ARR_TYPED);
				str(comp.getName());
				out.writeInt(n);
				for (Object x : (Object[]) arr)
					value(x);
			}
		}

		void str(String s) throws IOException {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			out.writeInt(b.length);
			out.write(b);
		}
	}

	// ================= reader =================

	private static final class R {
		final Vm vm;
		final DataInputStream in;
		final List<Object> objs = new ArrayList<>();
		final List<VmThread> threadById = new ArrayList<>();
		final List<Runnable> replays = new ArrayList<>();
		static final Object PEND = new Object(); // id slot reserved while its payload is in flight

		R(Vm vm, DataInputStream in) {
			this.vm = vm;
			this.in = in;
		}

		void run() throws IOException {
			byte[] magic = new byte[4];
			in.readFully(magic);
			ver = in.readShort();
			if (!"SNAP".equals(new String(magic, StandardCharsets.US_ASCII))
					|| (ver != 2 && ver != 3))
				throw new VmError("bad snapshot header");
			objs.add(null);
			threadById.add(null);

			// wipe live state: classes reset to pre-init, all runtime maps cleared
			for (VmClass c : vm.classes.values())
				if (!c.host)
					c.resetRuntime();
			// A prev thread whose serial is in this file was superseded by a
			// revived copy — its continuation belongs to the copy, so the
			// original is an orphan. A live host-entry thread absent from the
			// file was born after the save: keep it so its parked caller
			// completes and re-binds through serial proxies. Other post-save
			// threads reference the discarded graph — terminate them.
			List<VmThread> prev;
			synchronized (vm.threads) {
				prev = new ArrayList<>(vm.threads);
			}
			vm.threads.clear();
			vm.monitors.clear();
			synchronized (vm.proxies) {
				vm.proxies.clear();
			}
			synchronized (vm) {
				vm.liveBySerial.clear();
			}
			vm.greenThreads.clear();
			vm.greenTargets.clear();
			vm.guestArrayTypes.clear();
			vm.interned.clear();

			readGlobals();
			readClasses();
			int nt = readThreads();
			synchronized (vm.threads) {
				if (ver >= 3) {
					for (VmThread t : prev) {
						if (coveredSerials.contains(t.serial)) {
							t.restoreOrphan = true;
						} else if (t.isAlive()) {
							if (t.hostEntry) {
								vm.threads.add(t);
							} else {
								t.terminate();
							}
						}
					}
				} else {
					// v2 fallback: the file's threads occupied prev[0..nt) in
					// order — close enough for pre-serial saves
					for (int i = nt; i < prev.size(); i++) {
						VmThread t = prev.get(i);
						if (t.isAlive() && t.hostEntry)
							vm.threads.add(t);
					}
					for (int i = 0; i < nt && i < prev.size(); i++)
						prev.get(i).restoreOrphan = true;
				}
			}
			readMonitors();
			readArrayTypes();
			readBoxes();
			readProxies();
			vm.rrCursor = in.readInt();
			for (Object[] pi : pendingInit)
				((VmClass) pi[0]).initThread = threadById.get((Integer) pi[1]);
			for (Runnable r : replays)
				r.run();
		}

		/** {VmClass, initThreadTid} — resolved after all threads are read. */
		final List<Object[]> pendingInit = new ArrayList<>();
		int ver;
		/** serials of the threads this file carries (v3+): prev threads matching
		 *  one were superseded by a revived copy and must not double-run. */
		final Set<Long> coveredSerials = new HashSet<>();

		void readGlobals() throws IOException {
			int np = in.readInt();
			for (int i = 0; i < np; i++)
				vm.j2meProps.setProperty(str(), str());
			int ni = in.readInt();
			for (int i = 0; i < ni; i++) {
				String s = (String) value();
				vm.interned.put(s, s);
			}
		}

		void readClasses() throws IOException {
			int nc = in.readInt();
			for (int i = 0; i < nc; i++) {
				String name = str();
				VmClass c = vm.resolveClass(name);
				if (c == null || c.host || c.cf == null)
					throw new VmError("snapshot class " + name + " unresolvable");
				c.initDone = in.readBoolean();
				c.initRunning = in.readBoolean();
				pendingInit.add(new Object[]{c, in.readInt()});
				int ns = in.readInt();
				if (c.statics == null || ns != c.statics.length)
					throw new VmError("statics count mismatch for " + name);
				for (int s = 0; s < ns; s++)
					c.statics[s] = value();
			}
		}

		int readThreads() throws IOException {
			int nt = in.readInt();
			List<Integer> joinRaw = new ArrayList<>();
			List<int[]> waiterRaw = new ArrayList<>();
			for (int i = 0; i < nt; i++) {
				int tid = in.readInt();
				VmThread t = vm.newThread();
				threadById.add(t); // index == tid (ids assigned in list order)
				if (tid != threadById.size() - 1)
					throw new VmError("thread id out of order");
				if (ver >= 3) {
					t.serial = in.readLong();
					coveredSerials.add(t.serial);
				}
				t.state = in.readByte();
				long delta = in.readLong();
				t.wakeTime = t.state == VmThread.TIMED_WAITING
						? System.currentTimeMillis() + delta
						: 0;
				switch (in.readByte()) {
				case W_NONE:
					break;
				case W_MONITOR:
					t.waitMonitor = vm.monitorOf(value());
					if (t.state == VmThread.BLOCKED)
						((Vm.Monitor) t.waitMonitor).contenders.add(t);
					break;
				case W_PARKED:
					t.waitMonitor = Vm.PARKED;
					break;
				case W_CLINIT:
					t.waitMonitor = vm.resolveClass(str());
					break;
				default:
					throw new VmError("bad waitMonitor tag");
				}
				t.waitReleasedCount = in.readInt();
				t.resumingFromWait = in.readBoolean();
				t.interrupted = in.readBoolean();
				t.pendingInterrupt = in.readBoolean();
				t.interruptPc = in.readInt();
				joinRaw.add(in.readInt());
				int nw = in.readInt();
				int[] w = new int[nw];
				for (int j = 0; j < nw; j++)
					w[j] = in.readInt();
				waiterRaw.add(w);
				t.runTarget = value();
				t.hostThread = (Thread) value();
				t.result = value();
				t.nestedResult = value();
				if (in.readBoolean())
					t.lastError = new VmException(value());
				if (in.readBoolean()) {
					String key = str();
					int na = in.readInt();
					Object[] pa = new Object[na];
					for (int j = 0; j < na; j++)
						pa[j] = value();
					replays.add(() -> replayParked(t, key, pa));
				}
				int nf = in.readInt();
				for (int k = 0; k < nf; k++)
					t.frames.add(readFrame());
			}
			for (int i = 0; i < nt; i++) {
				VmThread t = threadById.get(i + 1);
				int jt = joinRaw.get(i);
				if (jt != 0)
					t.joinTarget = threadById.get(jt);
				for (int wt : waiterRaw.get(i)) {
					VmThread w = threadById.get(wt);
					if (w != null)
						t.joinWaiters.add(w);
				}
			}
			return nt;
		}

		void replayParked(VmThread t, String key, Object[] args) {
			Vm.ParkedCall pc = vm.parkedCalls.get(key);
			if (pc == null)
				throw new VmError("parked call " + key + " not registered");
			t.parkedCallKey = null;
			t.parkedArgs = null;
			vm.callParked(t, pc, args);
		}

		Frame readFrame() throws IOException {
			String cn = str(), mn = str(), md = str();
			VmClass owner = vm.resolveClass(cn);
			if (owner == null || owner.host)
				throw new VmError("frame owner " + cn + " not a guest class");
			ClassFile.Method m = owner.declaredMethods.get(mn + "|" + md);
			if (m == null)
				throw new VmError("no method " + cn + "." + mn + md);
			Frame f = vm.allocFrame(owner, m);
			f.pc = in.readInt();
			f.sp = in.readInt();
			f.callPc = in.readInt();
			f.fromCall = in.readBoolean();
			f.external = in.readBoolean();
			f.syncOn = value();
			int nl = in.readInt();
			if (nl != f.locals.length)
				throw new VmError("locals count mismatch in " + cn + "." + mn);
			for (int i = 0; i < nl; i++)
				f.locals[i] = value();
			if (f.sp > f.stack.length)
				throw new VmError("stack depth mismatch in " + cn + "." + mn);
			for (int i = 0; i < f.sp; i++)
				f.stack[i] = value();
			return f;
		}

		void readMonitors() throws IOException {
			int nm = in.readInt();
			for (int i = 0; i < nm; i++) {
				Object obj = value();
				Vm.Monitor m = vm.monitorOf(obj);
				m.owner = threadById.get(in.readInt());
				m.count = in.readInt();
				int nw = in.readInt();
				for (int j = 0; j < nw; j++) {
					VmThread w = threadById.get(in.readInt());
					if (w != null)
						m.waiters.add(w);
				}
			}
		}

		void readArrayTypes() throws IOException {
			int n = in.readInt();
			for (int i = 0; i < n; i++) {
				Object arr = objs.get(in.readInt());
				vm.guestArrayTypes.put(arr, vm.resolveClass(str()));
			}
		}

		void readBoxes() throws IOException {
			int nb = in.readInt();
			for (int i = 0; i < nb; i++) {
				Object box = value();
				VmThread t = threadById.get(in.readInt());
				Object run = value();
				vm.greenThreads.put(box, t);
				if (box instanceof Thread && run != null)
					vm.greenTargets.put((Thread) box, run);
			}
		}

		void readProxies() throws IOException {
			int np = in.readInt();
			for (int i = 0; i < np; i++) {
				String ifn = str();
				Object g = value();
				if (ifn.isEmpty() || !(g instanceof VmObject))
					continue;
				try {
					vm.bridge.proxyFor((VmObject) g, Class.forName(ifn));
				} catch (ClassNotFoundException e) {
					throw new VmError("proxy iface " + ifn + " missing", e);
				}
			}
		}

		// ---- values ----

		Object value() throws IOException {
			switch (in.readUnsignedByte()) {
			case T_NULL:
				return null;
			case T_INT:
				return in.readInt();
			case T_LONG:
				return in.readLong();
			case T_FLOAT:
				return in.readFloat();
			case T_DOUBLE:
				return in.readDouble();
			case T_BOOL:
				return in.readBoolean();
			case T_BYTE:
				return in.readByte();
			case T_SHORT:
				return in.readShort();
			case T_CHAR:
				return in.readChar();
			case T_WIDE:
				return Frame.WIDE;
			case T_CLASS: {
				VmClass c = vm.resolveClass(str());
				if (c == null)
					throw new VmError("Class ref unresolvable");
				return c.guestClass();
			}
			case T_REF:
				return ref();
			default:
				throw new VmError("bad value tag");
			}
		}

		Object ref() throws IOException {
			int id = in.readInt();
			if (id <= 0 || id > objs.size())
				throw new VmError("bad object id " + id);
			if (id < objs.size()) {
				Object o = objs.get(id);
				if (o == PEND)
					throw new VmError("cycle through in-flight handle record " + id);
				return o;
			}
			// reserve the slot before reading the payload: nested records may
			// emit ids ahead of their parent's materialization (HANDLE records)
			objs.add(PEND);
			Object o = readPayload(id);
			objs.set(id, o);
			return o;
		}

		Object readPayload(int id) throws IOException {
			int kind = in.readUnsignedByte();
			switch (kind) {
			case K_STR:
				return str();
			case K_OBJ:
				return readObj(id);
			case K_THREAD_BOX:
				return new Thread();
			case K_THROWABLE:
				return readThrowable();
			case K_PROXY:
				return value();
			case K_POBJ:
				return new Object();
			case K_HANDLE: {
				String cn = str();
				Object rec = value();
				HostHandle h = findHandle(cn);
				if (h == null)
					throw new VmError("no HostHandle for " + cn);
				return h.revive(vm, rec);
			}
			case K_ARR_B:
			case K_ARR_S:
			case K_ARR_C:
			case K_ARR_I:
			case K_ARR_Z:
			case K_ARR_J:
			case K_ARR_F:
			case K_ARR_D:
			case K_ARR_OBJ:
			case K_ARR_TYPED:
				return readArray(kind, id);
			default:
				throw new VmError("bad object kind");
			}
		}

		Object readObj(int id) throws IOException {
			String cn = str();
			VmClass c = vm.resolveClass(cn);
			if (c == null || c.host || c.cf == null)
				throw new VmError("object class " + cn + " unresolvable");
			VmObject o = new VmObject(c);
			objs.set(id, o); // early: cycles back into this object resolve
			long serial = in.readLong();
			if (serial != 0) {
				o.serial = serial;
				// stale proxies holding this serial rebind here; same monitor
				// as serialFor since proxyFor can mint on any host thread
				synchronized (vm) {
					vm.liveBySerial.put(serial, o);
				}
			}
			int nf = in.readInt();
			if (nf != o.fields.length)
				throw new VmError("field count mismatch for " + cn);
			for (int i = 0; i < nf; i++)
				o.fields[i] = value();
			if (in.readBoolean()) {
				String hb = str();
				VmClass hbc = vm.resolveClass(hb);
				Object rec = value();
				vm.constructShadow(o, hbc);
				if (o.hostShadow == null)
					throw new VmError("shadow factory missing for " + hb);
				ShadowCodec cdc = vm.shadowCodecs.get(hbc.hostClass);
				if (cdc == null)
					throw new VmError("no ShadowCodec for " + hb);
				cdc.restore(vm, o, o.hostShadow, rec);
			}
			o.superCtorArgs = (Object[]) value();
			o.exceptionCause = value();
			if (in.readBoolean())
				o.exceptionTrace = str();
			return o;
		}

		Object readArray(int kind, int id) throws IOException {
			VmClass cc = null;
			if (kind == K_ARR_TYPED)
				cc = vm.resolveClass(str());
			int n = in.readInt();
			Object a;
			switch (kind) {
			case K_ARR_B:
				a = new byte[n];
				break;
			case K_ARR_Z:
				a = new boolean[n];
				break;
			case K_ARR_S:
				a = new short[n];
				break;
			case K_ARR_C:
				a = new char[n];
				break;
			case K_ARR_I:
				a = new int[n];
				break;
			case K_ARR_J:
				a = new long[n];
				break;
			case K_ARR_F:
				a = new float[n];
				break;
			case K_ARR_D:
				a = new double[n];
				break;
			case K_ARR_OBJ:
				a = new Object[n];
				break;
			default: // K_ARR_TYPED — real runtime component type
				if (cc != null && cc.host) {
					a = Array.newInstance(cc.hostClass, n);
				} else {
					a = new Object[n];
					if (cc != null)
						vm.guestArrayTypes.put(a, cc);
				}
			}
			objs.set(id, a); // early: array self-cycles resolve
			switch (kind) {
			case K_ARR_B:
				in.readFully((byte[]) a);
				break;
			case K_ARR_Z:
				for (int i = 0; i < n; i++)
					((boolean[]) a)[i] = in.readBoolean();
				break;
			case K_ARR_S:
				for (int i = 0; i < n; i++)
					((short[]) a)[i] = in.readShort();
				break;
			case K_ARR_C:
				for (int i = 0; i < n; i++)
					((char[]) a)[i] = in.readChar();
				break;
			case K_ARR_I:
				for (int i = 0; i < n; i++)
					((int[]) a)[i] = in.readInt();
				break;
			case K_ARR_J:
				for (int i = 0; i < n; i++)
					((long[]) a)[i] = in.readLong();
				break;
			case K_ARR_F:
				for (int i = 0; i < n; i++)
					((float[]) a)[i] = in.readFloat();
				break;
			case K_ARR_D:
				for (int i = 0; i < n; i++)
					((double[]) a)[i] = in.readDouble();
				break;
			default:
				Object[] o = (Object[]) a;
				for (int i = 0; i < n; i++)
					o[i] = value();
			}
			return a;
		}

		Object readThrowable() throws IOException {
			String cn = str(), msg = str();
			try {
				Class<?> k = Class.forName(cn);
				if (!Throwable.class.isAssignableFrom(k))
					throw new VmError("not a throwable: " + cn);
				try {
					return k.getDeclaredConstructor(String.class).newInstance(msg);
				} catch (NoSuchMethodException e) {
					return k.getDeclaredConstructor().newInstance();
				}
			} catch (VmError e) {
				throw e;
			} catch (Exception e) {
				throw new VmError("cannot revive throwable " + cn, e);
			}
		}

		HostHandle findHandle(String className) throws IOException {
			Class<?> c;
			try {
				c = Class.forName(className);
			} catch (ClassNotFoundException e) {
				throw new VmError("handle class " + className + " missing", e);
			}
			for (Class<?> x = c; x != null; x = x.getSuperclass()) {
				HostHandle h = vm.hostHandles.get(x);
				if (h != null)
					return h;
				for (Class<?> i : x.getInterfaces()) {
					h = vm.hostHandles.get(i);
					if (h != null)
						return h;
				}
			}
			return null;
		}

		String str() throws IOException {
			byte[] b = new byte[in.readInt()];
			in.readFully(b);
			return new String(b, StandardCharsets.UTF_8);
		}
	}
}
