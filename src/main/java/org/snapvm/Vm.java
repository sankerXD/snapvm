package org.snapvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The virtual machine: guest class registry, green-thread scheduler, monitors,
 * host bridge. Single-threaded cooperative execution — the whole machine state
 * is plain data, which is what will make snapshots possible.
 */
public final class Vm {
	/** Source of guest .class bytes. */
	public interface ClassSource {
		byte[] findClass(String binaryName); // "java/lang/Object" style? no: dotted or slashed—see below
		InputStream findResource(String path);
	}

	final Map<String, VmClass> classes = new HashMap<>();
	final Map<String, String> interned = new HashMap<>();
	final IdentityHashMap<Object, Monitor> monitors = new IdentityHashMap<>();
	final IdentityHashMap<Object, Object> proxies = new IdentityHashMap<>();   // proxy -> guest obj
	final IdentityHashMap<Thread, Object> greenTargets = new IdentityHashMap<>(); // host Thread -> guest Runnable
	final IdentityHashMap<Object, VmClass> guestArrayTypes = new IdentityHashMap<>();
	final Properties j2meProps = new Properties();

	final HostBridge bridge = new HostBridge(this);

	private final List<ClassSource> sources = new ArrayList<>();
	private final List<VmThread> threads = new ArrayList<>();
	private final Map<Class<?>, ShadowFactory> shadowFactories = new HashMap<>();
	private final Object lock = new Object();
	private final Object execLock = new Object(); // serializes all guest execution
	/** guest-visible thread key (host Thread or VmObject for guest-extends-Thread) -> green thread */
	final IdentityHashMap<Object, VmThread> greenThreads = new IdentityHashMap<>();
	private VmThread executor;
	private Thread executorThread;

	static final class Monitor {
		VmThread owner;
		int count;
		final List<VmThread> waiters = new ArrayList<>();
	}

	public Vm() {
		j2meProps.setProperty("microedition.platform", "JL-Modern");
		j2meProps.setProperty("microedition.encoding", "UTF-8");
	}

	/** register a classpath. dirMode: findClass gets "com/foo/Bar" -> bytes. */
	public void addClassSource(ClassSource src) {
		sources.add(src);
	}

	/** Value returned to guest code for {@code System.getProperty(key)}. */
	public void setJ2meProperty(String key, String value) {
		if (value != null)
			j2meProps.setProperty(key, value);
	}

	public void addJar(ZipFile jar) {
		addClassSource(new ClassSource() {
			public byte[] findClass(String name) {
				ZipEntry e = jar.getEntry(name + ".class");
				if (e == null)
					return null;
				try (InputStream in = jar.getInputStream(e)) {
					return readAll(in);
				} catch (IOException x) {
					return null;
				}
			}

			public InputStream findResource(String path) {
				ZipEntry e = jar.getEntry(path);
				try {
					return e == null ? null : jar.getInputStream(e);
				} catch (IOException x) {
					return null;
				}
			}
		});
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
			bos.write(buf, 0, n);
		return bos.toByteArray();
	}

	/** resolve "java/lang/Object" or "[I" or "java.lang.Object" style names. */
	public VmClass resolveClass(String name) {
		name = name.replace('.', '/');
		VmClass c = classes.get(name);
		if (c != null)
			return c;
		// array types
		if (name.startsWith("[")) {
			Class<?> hostArr = tryHostArray(name);
			c = hostArr != null ? new VmClass(this, name, hostArr) : new VmClass(this, name, (ClassFile) null);
			c.access = 0;
			c.superClass = resolveClass("java/lang/Object");
			classes.put(name, c);
			return c;
		}
		// guest first
		for (ClassSource s : sources) {
			byte[] b = s.findClass(name);
			if (b != null) {
				try {
					ClassFile cf = ClassFile.parse(b);
					c = new VmClass(this, cf.name, cf);
					classes.put(cf.name, c);
					c.link();
					return c;
				} catch (IOException e) {
					throw new VmError("bad class " + name, e);
				}
			}
		}
		// host fallback
		try {
			Class<?> hc = Class.forName(name.replace('/', '.'));
			c = new VmClass(this, name, hc);
			classes.put(name, c);
			return c;
		} catch (ClassNotFoundException e) {
			throw new VmException(new NoClassDefFoundError(name.replace('/', '.')));
		}
	}

	private Class<?> tryHostArray(String name) {
		int dims = 0;
		while (name.charAt(dims) == '[') dims++;
		String elem = name.substring(dims);
		Class<?> ec;
		switch (elem.charAt(0)) {
			case 'I': ec = int.class; break;
			case 'J': ec = long.class; break;
			case 'F': ec = float.class; break;
			case 'D': ec = double.class; break;
			case 'B': ec = byte.class; break;
			case 'C': ec = char.class; break;
			case 'S': ec = short.class; break;
			case 'Z': ec = boolean.class; break;
			case 'L':
				String en = elem.substring(1, elem.length() - 1).replace('/', '.');
				try {
					ec = Class.forName(en);
				} catch (ClassNotFoundException e) {
					return null;
				}
				break;
			default:
				return null;
		}
		return java.lang.reflect.Array.newInstance(ec, new int[dims]).getClass();
	}

	String intern(String s) {
		String r = interned.get(s);
		if (r == null) {
			interned.put(s, s);
			return s;
		}
		return r;
	}

	public InputStream getResource(String path) {
		for (ClassSource s : sources) {
			InputStream in = s.findResource(path);
			if (in != null)
				return in;
		}
		return null;
	}

	// ---------- scheduling ----------

	VmThread newThread() {
		VmThread t = new VmThread(this);
		synchronized (threads) {
			threads.add(t);
		}
		return t;
	}

	void wake(VmThread t) {
		t.state = VmThread.RUNNABLE;
	}

	/** Debug dump: state + top frames of every live green thread. */
	public String dumpThreads() {
		StringBuilder sb = new StringBuilder();
		synchronized (threads) {
			for (VmThread t : threads) {
				if (!t.isAlive())
					continue;
				sb.append("state=").append(t.state)
						.append(t.waitMonitor != null ? " waitMon" : "")
						.append(" frames=").append(t.frames.size()).append('\n');
				for (int i = t.frames.size() - 1; i >= 0 && i > t.frames.size() - 4; i--) {
					Frame f = t.frames.get(i);
					sb.append("  at ").append(f.owner.name).append('.')
							.append(f.method.name).append(f.method.desc)
							.append(" @").append(f.pc).append('\n');
				}
			}
		}
		return sb.toString();
	}

	/** Debug: flattened view of a guest object's instance fields (name|desc -> value). */
	public Map<String, Object> fieldValues(VmObject obj) {
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, Integer> e : obj.clazz.fieldSlots.entrySet())
			out.put(e.getKey(), obj.fields[e.getValue()]);
		return out;
	}

	public void pump() {
		pump(0);
	}

	/** run the scheduler until idle or budget (total instructions) exhausted. */
	public void pump(long budget) {
		synchronized (execLock) {
			long used = 0;
			for (;;) {
				VmThread t = nextRunnable();
				if (t == null)
					break;
				used += runSlice(t, budget > 0 ? Math.max(1, budget - used) : Long.MAX_VALUE);
				if (budget > 0 && used >= budget)
					break;
			}
		}
	}

	private VmThread nextRunnable() {
		long now = System.currentTimeMillis();
		synchronized (threads) {
			for (VmThread t : threads) {
				if (t.state == VmThread.RUNNABLE && !t.frames.isEmpty())
					return t;
				if (t.state == VmThread.TIMED_WAITING && now >= t.wakeTime)
					return t; // Interpreter will retry its op (state restored inside)
			}
		}
		return null;
	}

	// ---------- monitors ----------

	Monitor monitorOf(Object obj) {
		synchronized (monitors) {
			Monitor m = monitors.get(obj);
			if (m == null) {
				m = new Monitor();
				monitors.put(obj, m);
			}
			return m;
		}
	}

	/** @return true if acquired; false -> thread parked BLOCKED, retry on wake. */
	boolean monEnter(VmThread t, Object obj) {
		Monitor m = monitorOf(obj);
		synchronized (m) {
			if (m.owner == null) {
				m.owner = t;
				m.count = 1;
				return true;
			}
			if (m.owner == t) {
				m.count++;
				return true;
			}
			t.state = VmThread.BLOCKED;
			t.waitMonitor = m;
			return false;
		}
	}

	void monExit(VmThread t, Object obj) {
		Monitor m = monitorOf(obj);
		synchronized (m) {
			if (m.owner != t) {
				throw new VmException(new IllegalMonitorStateException("not owner"));
			}
			if (--m.count == 0) {
				m.owner = null;
				// wake one blocked contender so it can retry monEnter
				synchronized (threads) {
					for (VmThread w : threads) {
						if (w.state == VmThread.BLOCKED && w.waitMonitor == m) {
							w.state = VmThread.RUNNABLE;
							break;
						}
					}
				}
			}
		}
	}

	void objWait(VmThread t, Object obj, long ms) {
		Monitor m = monitorOf(obj);
		synchronized (m) {
			if (m.owner != t)
				throw new VmException(new IllegalMonitorStateException("wait without monitor"));
			int released = m.count;
			m.owner = null;
			m.count = 0;
			m.waiters.add(t);
			t.state = ms > 0 ? VmThread.TIMED_WAITING : VmThread.WAITING;
			t.wakeTime = ms > 0 ? System.currentTimeMillis() + ms : 0;
			t.waitMonitor = m;
			t.waitReleasedCount = released;
			t.resumingFromWait = true;
			synchronized (threads) {
				for (VmThread w : threads) {
					if (w.state == VmThread.BLOCKED && w.waitMonitor == m) {
						w.state = VmThread.RUNNABLE;
						break;
					}
				}
			}
		}
	}

	void objNotify(VmThread t, Object obj, boolean all) {
		Monitor m = monitorOf(obj);
		synchronized (m) {
			if (m.owner != t)
				throw new VmException(new IllegalMonitorStateException("notify without monitor"));
			int n = all ? m.waiters.size() : Math.min(1, m.waiters.size());
			for (int i = 0; i < n; i++) {
				VmThread w = m.waiters.remove(0);
				w.state = VmThread.BLOCKED; // must reacquire; Interpreter retries monEnter
			}
		}
	}

	// ---------- entry points ----------

	public Object invokeStatic(String className, String method, String desc, Object... args) {
		VmClass c = resolveClass(className);
		VmThread t = newThread();
		t.hostThread = Thread.currentThread();
		ensureInitBlocking(c, t);
		pushStatic(t, c, method, desc, args);
		runUntilDone(t);
		if (t.lastError != null)
			throw t.lastError;
		return t.result;
	}

	/**
	 * Allocate a guest object and run its no-arg {@code <init>}. Runs on the
	 * calling thread; for guest classes extending host classes the adapter
	 * shadow (if registered) is attached automatically.
	 */
	public VmObject newGuestObject(String className) {
		VmClass c = resolveClass(className);
		if (c.host)
			throw new VmError("not a guest class: " + className);
		VmThread t = newThread();
		t.hostThread = Thread.currentThread();
		ensureInitBlocking(c, t);
		VmObject o = new VmObject(c);
		pushInvoke(t, o, "<init>", "()V", new Object[]{o});
		runUntilDone(t);
		if (t.lastError != null)
			throw t.lastError;
		return o;
	}

	public Object invokeVirtual(Object recv, String name, String desc, Object... args) {
		VmThread t = newThread();
		t.hostThread = Thread.currentThread();
		Object[] a = joinArgs(recv, args);
		if (recv instanceof VmObject) {
			pushInvoke(t, recv, name, desc, a);
			runUntilDone(t);
			if (t.lastError != null)
				throw t.lastError;
			return t.result;
		}
		// host receiver: direct reflective call
		VmClass hc = resolveClass(recv.getClass().getName());
		return bridge.call(t, hc.name, name, desc, a, 0);
	}

	/**
	 * Drive the entry thread to completion, interleaving other green threads
	 * while it is parked. execLock is only held per slice — never across the
	 * park wait — so a blocking host call (e.g. parked serviceRepaints waiting
	 * on an event-queue marker) cannot deadlock against another host executor
	 * that needs the lock to produce the wakeup.
	 */
	private void runUntilDone(VmThread t) {
		for (;;) {
			synchronized (execLock) {
				if (canRun(t))
					runSlice(t, Long.MAX_VALUE);
				if (!t.isAlive())
					return;
				if (canRun(t))
					continue;
				// t parked: let other green threads try to unblock it
				pump();
				if (!t.isAlive() || canRun(t))
					continue;
				if (pendingWork() < 0)
					return;
			}
			// parked with work pending elsewhere — release the lock and yield
			try {
				Thread.sleep(1);
			} catch (InterruptedException ignored) {
			}
		}
	}

	/**
	 * One execute() slice on the current host thread. executor/executorThread
	 * are only set for the duration of Interpreter.execute: callFromHost treats
	 * executorThread==currentThread as permission to nest on executor, so both
	 * must be null between slices and restored even when a VmError escapes —
	 * a stale executorThread makes every later host callback on this thread
	 * NPE on a null executor and get silently dropped by the caller.
	 */
	private long runSlice(VmThread t, long quota) {
		executor = t;
		Thread prev = executorThread;
		executorThread = Thread.currentThread();
		try {
			return Interpreter.execute(this, t, quota);
		} finally {
			executor = null;
			executorThread = prev;
		}
	}

	private static boolean canRun(VmThread t) {
		if (t.frames.isEmpty())
			return false;
		if (t.state == VmThread.RUNNABLE)
			return true;
		return t.state == VmThread.TIMED_WAITING
				&& System.currentTimeMillis() >= t.wakeTime;
	}

	/** @return ms until the next timed wake (0 = runnable work exists), -1 if nothing can progress. */
	private long pendingWork() {
		long now = System.currentTimeMillis();
		long best = Long.MAX_VALUE;
		synchronized (threads) {
			for (VmThread u : threads) {
				if (!u.isAlive() || u.frames.isEmpty())
					continue;
				if (u.state == VmThread.RUNNABLE)
					return 0;
				if (u.state == VmThread.TIMED_WAITING)
					best = Math.min(best, Math.max(0, u.wakeTime - now));
			}
		}
		return best == Long.MAX_VALUE ? -1 : best;
	}

	void pushStatic(VmThread t, VmClass c, String name, String desc, Object[] args) {
		ensureInitBlocking(c, t);
		ClassFile.Method m = c.findDeclared(name, desc);
		if (m == null || (m.access & 0x0008) == 0)
			throw new VmError("no static " + c.name + "." + name + desc);
		Frame f = new Frame(c, m);
		fillArgs(f, desc, args, false);
		pushFrame(t, f);
	}

	/**
	 * Push a frame on an entry-point thread. The thread may have been marked
	 * TERMINATED by a nested run (e.g. a blocking clinit); revive it so the
	 * scheduler picks it up again.
	 */
	private static void pushFrame(VmThread t, Frame f) {
		t.frames.add(f);
		if (t.state == VmThread.TERMINATED)
			t.state = VmThread.RUNNABLE;
	}

	void pushInvoke(VmThread t, Object recv, String name, String desc, Object[] args) {
		ClassFile.Method m;
		VmClass owner;
		if (recv instanceof VmObject) {
			owner = ((VmObject) recv).clazz;
			// <init> is not inherited; merged methodMap would find a parent's
			m = name.equals("<init>")
					? owner.declaredMethods.get(name + "|" + desc)
					: owner.findMethod(name, desc);
			if (m == null) {
				VmClass hb = owner.hostBoundary();
				Object r = bridge.call(t, hb != null ? hb.name : "java/lang/Object",
						name, desc, args, 0);
				t.nestedResult = r;
				t.result = r;
				if (t.frames.isEmpty())
					t.terminate();
				return;
			}
			ensureInitBlocking(owner, t);
			Frame f = new Frame(ownerOf(owner, m), m);
			fillArgs(f, desc, args, true);
			pushFrame(t, f);
			return;
		}
		VmClass hc = resolveClass(recv.getClass().getName());
		Object r = bridge.call(t, hc.name, name, desc, args, 0);
		t.nestedResult = r;
		t.result = r;
		if (t.frames.isEmpty())
			t.terminate();
	}

	private VmClass ownerOf(VmClass start, ClassFile.Method m) {
		for (VmClass c = start; c != null && !c.host; c = c.superClass)
			if (c.declaredMethods.containsValue(m))
				return c;
		return start;
	}

	static void fillArgs(Frame f, String desc, Object[] args, boolean hasThis) {
		int li = 0;
		int ai = 0;
		if (hasThis)
			f.locals[li++] = args[ai++];
		for (int i = 1; desc.charAt(i) != ')'; i++) {
			char c = desc.charAt(i);
			if (c == '[') {
				while (desc.charAt(i) == '[')
					i++;
				c = desc.charAt(i);
			}
			if (c == 'L')
				i = desc.indexOf(';', i);
			Object v = args[ai++];
			f.locals[li++] = fromHost(v);
			if (c == 'J' || c == 'D')
				f.locals[li++] = Frame.WIDE;
		}
	}

	/**
	 * host->guest call; safe to invoke from any host thread. On the executor thread
	 * it nests into the current guest stack; from any other thread it waits for the
	 * single-executor gate and runs the call on this thread.
	 */
	public Object callFromHost(final Object recv, final String name, final String desc,
			final Object... args) {
		VmThread cur;
		if (Thread.currentThread() == executorThread && (cur = executor) != null) {
			if (!(recv instanceof VmObject)) {
				VmClass hc = resolveClass(recv.getClass().getName());
				return bridge.call(cur, hc.name, name, desc, joinArgs(recv, args), 0);
			}
			int depth = cur.frames.size();
			pushInvoke(cur, recv, name, desc, joinArgs(recv, args));
			Interpreter.execute(this, cur, Long.MAX_VALUE, depth);
			return cur.nestedResult;
		}
		VmThread t = newThread();
		t.hostThread = Thread.currentThread();
		synchronized (execLock) {
			pushInvoke(t, recv, name, desc, joinArgs(recv, args));
		}
		runUntilDone(t);
		if (t.lastError != null)
			throw t.lastError;
		return t.result;
	}

	private static Object[] joinArgs(Object recv, Object[] args) {
		Object[] a = new Object[args.length + 1];
		a[0] = recv;
		System.arraycopy(args, 0, a, 1, args.length);
		return a;
	}

	// ---------- class init / green threads ----------

	/**
	 * Run &lt;clinit&gt; if needed. Returns true when safe to proceed; false when a
	 * clinit frame was pushed (caller should retry its instruction) or the thread
	 * was parked waiting for another thread's init.
	 */
	boolean ensureInit(VmClass c, VmThread t) {
		for (VmClass x = c; x != null && !x.host; x = x.superClass) {
			if (x.initDone || x.initRunning && x.initThread == t)
				continue;
			synchronized (x) {
				if (x.initDone)
					continue;
				if (x.initRunning) {
					// another green thread initializing: park until it finishes
					t.state = VmThread.BLOCKED;
					t.waitMonitor = x;
					return false;
				}
				x.initRunning = true;
				x.initThread = t;
			}
			// only x's OWN <clinit> — findDeclared walks supers and would return
			// a parent's, whose bytecode indexes a different constant pool
			ClassFile.Method clinit = x.declaredMethods.get("<clinit>|()V");
			if (clinit == null) {
				finishInit(x);
				continue;
			}
			// apply ConstantValue defaults for static finals first
			for (Map.Entry<String, Object> e : x.staticDefaults.entrySet()) {
				Integer s = x.staticFieldSlots.get(e.getKey());
				if (s != null)
					x.statics[s] = e.getValue();
			}
			Frame f = new Frame(x, clinit);
			t.frames.add(f);
			return false;
		}
		return true;
	}

	void finishInit(VmClass c) {
		synchronized (c) {
			c.initDone = true;
			c.initRunning = false;
			c.initThread = null;
			c.notifyAll();
		}
		synchronized (threads) {
			for (VmThread w : threads)
				if (w.state == VmThread.BLOCKED && w.waitMonitor == c) {
					w.state = VmThread.RUNNABLE;
					w.waitMonitor = null;
				}
		}
	}

	/** blocking variant for entry points (no caller frame to retry). */
	void ensureInitBlocking(VmClass c, VmThread t) {
		if (ensureInit(c, t))
			return;
		// a clinit frame was pushed; run this thread until it completes.
		// an entry thread may already be TERMINATED from a previous nested run
		if (t.state == VmThread.TERMINATED)
			t.state = VmThread.RUNNABLE;
		while (!c.initDone && t.isAlive()) {
			pump();
			if (c.initDone || !t.isAlive())
				break;
			long nap = pendingWork();
			if (nap < 0)
				break; // init can't complete — let the failure surface downstream
			if (nap > 0) {
				try {
					Thread.sleep(Math.min(nap, 5));
				} catch (InterruptedException ignored) {
				}
			}
		}
	}

	void greenSleep(VmThread t, long ms) {
		t.state = VmThread.TIMED_WAITING;
		t.wakeTime = System.currentTimeMillis() + ms;
	}

	// ---------- host-shadow support (guest class extends host class) ----------

	/** Adapter registers a factory producing a real host object per guest instance. */
	public interface ShadowFactory {
		Object create(Vm vm, VmObject guest);
	}

	public void registerShadow(Class<?> hostBase, ShadowFactory f) {
		shadowFactories.put(hostBase, f);
	}

	/**
	 * Called when guest code runs {@code invokespecial <init>} on a host
	 * superclass: manifests the shadow object, whose own construction runs the
	 * real host constructor. No-op if the shadow already exists or no factory
	 * is registered (super-ctor effects are then skipped).
	 */
	void constructShadow(VmObject guest, VmClass hostBoundary) {
		if (guest.hostShadow != null)
			return;
		ShadowFactory f = shadowFactories.get(hostBoundary.hostClass);
		if (f != null)
			guest.hostShadow = f.create(this, guest);
	}

	/** marshal-side variant: the shadow is required, throw if it can't exist. */
	Object requireShadow(VmObject guest, Class<?> target) {
		if (guest.hostShadow != null && target.isInstance(guest.hostShadow))
			return guest.hostShadow;
		VmClass hb = guest.clazz.hostBoundary();
		if (hb == null || !target.isAssignableFrom(hb.hostClass))
			throw new VmError("cannot marshal " + guest.clazz.name + " to " + target.getName());
		constructShadow(guest, hb);
		if (guest.hostShadow == null)
			throw new VmError("no shadow factory registered for " + hb.name);
		return guest.hostShadow;
	}

	/** host -> guest: unwrap shadows; normalize int-family primitives to Integer. */
	static Object fromHost(Object v) {
		if (v instanceof GuestShadow)
			return ((GuestShadow) v).guest();
		if (v instanceof Boolean)
			return (Boolean) v ? 1 : 0;
		if (v instanceof Character)
			return (int) (Character) v;
		if (v instanceof Byte || v instanceof Short)
			return ((Number) v).intValue();
		return v;
	}

	void park(VmThread t, long ms) {
		if (ms > 0) {
			greenSleep(t, ms);
		} else {
			t.state = VmThread.WAITING;
		}
	}

	VmThread greenOf(Object th) {
		synchronized (greenThreads) {
			return greenThreads.get(th);
		}
	}

	/**
	 * Launch a green thread for a host Thread constructed with a guest Runnable,
	 * or for a guest class extending Thread (key == the VmObject itself, whose
	 * run() override is the target).
	 */
	void startGreen(Object key, VmThread starter) {
		Object target = key instanceof VmObject ? key : null;
		if (target == null) {
			synchronized (greenTargets) {
				target = greenTargets.get(key);
			}
		}
		if (target == null)
			return; // Thread with no runnable target: start() is a no-op
		VmThread nt = newThread();
		if (key instanceof Thread)
			nt.hostThread = (Thread) key;
		synchronized (greenThreads) {
			greenThreads.put(key, nt);
		}
		Object recv = target;
		pushInvoke(nt, recv, "run", "()V", new Object[]{recv});
	}

	// ---------- parked host calls ----------

	/**
	 * A host-call intercept that parks the calling green thread instead of
	 * blocking a real host thread (e.g. Canvas.serviceRepaints). The handler
	 * performs the host-side action and invokes {@code done} when the guest
	 * call may resume; {@code done} may run on any thread, including
	 * synchronously before {@code invoke} returns.
	 */
	public interface ParkedCall {
		void invoke(Vm vm, VmThread t, Object[] args, Runnable done);
	}

	private final Map<String, ParkedCall> parkedCalls = new HashMap<>();
	private static final Object PARKED = new Object();

	/** key format: "javax/microedition/lcdui/Canvas.serviceRepaints()V" */
	public void registerParkedCall(String methodKey, ParkedCall call) {
		parkedCalls.put(methodKey, call);
	}

	ParkedCall parkedCall(String ownerName, String name, String desc) {
		String suffix = "." + name + desc;
		ParkedCall pc = parkedCalls.get(ownerName + suffix);
		if (pc != null)
			return pc;
		// the call site names a guest class's host boundary (e.g. FullCanvas),
		// not the registering class — walk the host hierarchy
		try {
			for (Class<?> h = Class.forName(ownerName.replace('/', '.'));
					h != null; h = h.getSuperclass()) {
				pc = parkedCalls.get(h.getName().replace('.', '/') + suffix);
				if (pc != null)
					return pc;
				for (Class<?> i : h.getInterfaces()) {
					pc = parkedCalls.get(i.getName().replace('.', '/') + suffix);
					if (pc != null)
						return pc;
				}
			}
		} catch (ClassNotFoundException ignored) {
		}
		return null;
	}

	/** Run a parked call: the green thread sleeps until the handler's done. */
	Object callParked(VmThread t, ParkedCall pc, Object[] args) {
		t.state = VmThread.WAITING;
		t.waitMonitor = PARKED;
		final boolean[] resumed = {false};
		Runnable done = () -> {
			synchronized (threads) {
				if (resumed[0])
					return;
				resumed[0] = true;
				t.waitMonitor = null;
				if (t.state == VmThread.WAITING)
					t.state = VmThread.RUNNABLE;
			}
		};
		try {
			pc.invoke(this, t, args, done);
		} catch (RuntimeException e) {
			done.run();
			throw e;
		}
		return null;
	}

	/** Whether the method is declared in guest code below the host boundary —
	 *  shadows use this to forward only callbacks the guest actually overrode. */
	public boolean guestDeclares(VmObject obj, String name, String desc) {
		String key = name + "|" + desc;
		for (VmClass c = obj.clazz; c != null && !c.host; c = c.superClass)
			if (c.declaredMethods.containsKey(key))
				return true;
		return false;
	}

	/** Marshal a guest value to a host-typed one (shadow/proxy/raw). */
	public Object toHost(Object v, Class<?> target) {
		return bridge.toHost(v, target);
	}

	/** A dead thread releases every monitor it still owned, waking contenders. */
	void releaseMonitors(VmThread t) {
		synchronized (monitors) {
			for (Monitor m : monitors.values())
				synchronized (m) {
					if (m.owner == t) {
						m.owner = null;
						m.count = 0;
					}
				}
		}
		synchronized (threads) {
			for (VmThread w : threads)
				if (w.state == VmThread.BLOCKED && w.waitMonitor instanceof Monitor
						&& ((Monitor) w.waitMonitor).owner == null)
					w.state = VmThread.RUNNABLE; // re-contends or re-parks
		}
	}
}
