package org.snapvm;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * Boundary between interpreted guest code and real host objects: reflective
 * dispatch with automatic marshaling plus the java.lang.* semantics a JVM must
 * provide itself (Object/Class/Thread/System monitors, identity, green threads).
 */
final class HostBridge {
	private final Vm vm;
	private final Map<String, Method> methodCache = new HashMap<>();
	private final Map<String, Field> fieldCache = new HashMap<>();
	private final Map<String, Constructor<?>> ctorCache = new HashMap<>();
	// owner "/" name -> host Class — Class.forName on ART is a classloader
	// walk (ICU/locale internals show up in profilers); games issue thousands
	// of host calls per frame so this must be a map hit
	private final Map<String, Class<?>> hostClasses = new HashMap<>();

	/** diagnostics: per-callsite host-call counts (owner.name). */
	public final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> callStats =
			new java.util.concurrent.ConcurrentHashMap<>();
	/** Per-call stat counting costs a concat + map ops — diagnostics only. */
	public volatile boolean statsOn = false;

	HostBridge(Vm vm) {
		this.vm = vm;
	}

	static final class NewMarker {
		final VmClass cls;

		NewMarker(VmClass cls) {
			this.cls = cls;
		}
	}

	// ---------- argument marshaling ----------

	Object toHost(Object v, Class<?> param) {
		if (v == null)
			return null;
		if (v instanceof Integer && param.isPrimitive() && param != int.class) {
			int i = (Integer) v;
			if (param == boolean.class) return i != 0;
			if (param == byte.class) return (byte) i;
			if (param == short.class) return (short) i;
			if (param == char.class) return (char) i;
			if (param == long.class) return (long) i;
			if (param == float.class) return (float) i;
			if (param == double.class) return (double) i;
		}
		if (v instanceof VmObject) {
			if (param == Object.class)
				return v;
			VmObject g = (VmObject) v;
			if (g.hostShadow != null && param.isInstance(g.hostShadow))
				return g.hostShadow;
			if (param.isInterface())
				return proxyFor(g, param);
			return vm.requireShadow(g, param);
		}
		if (v instanceof GuestShadow)
			return ((GuestShadow) v).guest(); // shouldn't happen inbound; keep symmetric
		if (v instanceof GuestClass) {
			if (param == Class.class) {
				VmClass gc = ((GuestClass) v).vmClass();
				if (gc.host)
					return gc.hostClass;
				throw new VmError("cannot marshal guest Class to host");
			}
			throw new VmError("cannot marshal Class to " + param.getName());
		}
		return v;
	}

	Object proxyFor(final VmObject guest, final Class<?> iface) {
		synchronized (vm.proxies) {
			// one proxy per (guest, iface): event-driven games re-proxy the same
			// Runnable on every tick — uncached this floods vm.proxies and the
			// snapshot with tens of thousands of identical entries
			Object cached = guest.proxies == null ? null : guest.proxies.get(iface);
			if (cached != null)
				return cached;
			final long serial = vm.serialFor(guest);
			Object p = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
				new InvocationHandler() {
					public Object invoke(Object proxy, Method method, Object[] args) {
						// Re-resolve per invoke: after Vm.restore the captured guest
						// is stale, but its serial aliases to the revived object.
						// callForSerial resolves + enqueues under one execLock
						// section so a restore can't land between the two.
						Class<?> rt = method.getReturnType();
						Object[] in = args == null ? new Object[0] : args.clone();
						Class<?>[] pts = method.getParameterTypes();
						for (int i = 0; i < in.length; i++)
							in[i] = Vm.fromHost(in[i], pts[i].isPrimitive());
						Object r = vm.callForSerial(serial, method.getName(),
								descOf(method), in);
						if (r == Vm.DROPPED) {
							droppedSerial(serial, method);
							return defaultValue(rt);
						}
						return toHost(r, rt);
					}
				});
			vm.proxies.put(p, guest);
			if (guest.proxies == null)
				guest.proxies = new HashMap<>();
			guest.proxies.put(iface, p);
			return p;
		}
	}

	private static int droppedSerialCount;

	private void droppedSerial(long serial, Method m) {
		if (droppedSerialCount++ < 100)
			System.err.println("GuestDrop: " + m.getName() + " on dead serial " + serial);
	}

	private static Object defaultValue(Class<?> rt) {
		if (!rt.isPrimitive() || rt == void.class)
			return null;
		if (rt == boolean.class) return false;
		if (rt == long.class) return 0L;
		if (rt == float.class) return 0f;
		if (rt == double.class) return 0d;
		return 0;
	}

	static String descOf(Method m) {
		StringBuilder sb = new StringBuilder("(");
		for (Class<?> p : m.getParameterTypes())
			sb.append(descOf(p));
		return sb.append(')').append(descOf(m.getReturnType())).toString();
	}

	static String descOf(Class<?> c) {
		if (c == void.class) return "V";
		if (c == int.class) return "I";
		if (c == long.class) return "J";
		if (c == float.class) return "F";
		if (c == double.class) return "D";
		if (c == byte.class) return "B";
		if (c == char.class) return "C";
		if (c == short.class) return "S";
		if (c == boolean.class) return "Z";
		if (c.isArray()) return c.getName().replace('.', '/');
		return "L" + c.getName().replace('.', '/') + ";";
	}

	// ---------- member resolution ----------

	private Method findMethod(Class<?> owner, String name, String desc, boolean statik) {
		String key = owner.getName() + "." + name + desc + (statik ? "#s" : "#i");
		Method m = methodCache.get(key);
		if (m != null)
			return m;
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			for (Method mm : c.getDeclaredMethods()) {
				if (mm.getName().equals(name) && descOf(mm).equals(desc)) {
					m = mm;
					break;
				}
			}
			if (m != null)
				break;
		}
		if (m == null && owner.isInterface()) {
			for (Method mm : owner.getMethods())
				if (mm.getName().equals(name) && descOf(mm).equals(desc)) {
					m = mm;
					break;
				}
		}
		if (m != null) {
			m.setAccessible(true);
			methodCache.put(key, m);
			return m;
		}
		throw new VmException(new NoSuchMethodError(owner.getName() + "." + name + desc));
	}

	Field findField(Class<?> owner, String name, String desc, boolean statik) {
		String key = owner.getName() + "." + name + ":" + desc + (statik ? "#s" : "#i");
		Field f = fieldCache.get(key);
		if (f != null)
			return f;
		for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
			try {
				f = c.getDeclaredField(name);
				break;
			} catch (NoSuchFieldException ignored) {
			}
		}
		if (f != null) {
			f.setAccessible(true);
			fieldCache.put(key, f);
			return f;
		}
		throw new VmException(new NoSuchFieldError(owner.getName() + "." + name));
	}

	private Constructor<?> findCtor(Class<?> owner, String desc) {
		String key = owner.getName() + ".<init>" + desc;
		Constructor<?> c = ctorCache.get(key);
		if (c != null)
			return c;
		for (Constructor<?> cc : owner.getDeclaredConstructors()) {
			StringBuilder sb = new StringBuilder("(");
			for (Class<?> p : cc.getParameterTypes())
				sb.append(descOf(p));
			if (sb.append(')').append('V').toString().equals(desc)) {
				c = cc;
				break;
			}
		}
		if (c == null)
			throw new VmException(new NoSuchMethodError(owner.getName() + ".<init>" + desc));
		c.setAccessible(true);
		ctorCache.put(key, c);
		return c;
	}

	private Object[] marshalArgs(Method m, Object[] args, int off) {
		Class<?>[] pts = m.getParameterTypes();
		Object[] out = new Object[pts.length];
		for (int i = 0; i < pts.length; i++)
			out[i] = toHost(args[off + i], pts[i]);
		return out;
	}

	// ---------- the dispatch ----------

	/**
	 * Invoke a host-class member. kind: 0 virtual/interface (args[0]=recv),
	 * 1 static, 2 special (private/super — called on recv reflectively),
	 * 3 constructor (returns new object, args exclude marker).
	 */
	Object call(VmThread t, String ownerName, String name, String desc, Object[] args, int kind) {
		return call0(t, ownerName, name, desc, args, kind);
	}

	private Object call0(VmThread t, String ownerName, String name, String desc, Object[] args, int kind) {
		if (statsOn) {
			java.util.concurrent.atomic.AtomicLong ctr = callStats.get(ownerName + "." + name);
			if (ctr == null) {
				callStats.putIfAbsent(ownerName + "." + name,
						new java.util.concurrent.atomic.AtomicLong());
				ctr = callStats.get(ownerName + "." + name);
			}
			ctr.incrementAndGet();
		}
		// VM-provided semantics first
		Object r = builtin(t, ownerName, name, desc, args, kind);
		if (r != BuiltinMarker.NOT_HANDLED)
			return Vm.fromHost(r);
		// adapter-registered blocking calls park the green thread until done
		Vm.ParkedCall pc = vm.parkedCall(ownerName, name, desc);
		if (pc != null)
			return vm.callParked(t, pc, args);

		// adapter-registered direct calls — guest receivers must marshal first
		if (!(kind == 0 && args.length > 0 && args[0] instanceof VmObject)) {
			Vm.FastCall fc = vm.fastCall(ownerName, name, desc);
			if (fc != null) {
				try {
					return Vm.fromHost(fc.invoke(args));
				} catch (VmError | VmException e) {
					throw e;
				} catch (Exception e) {
					throw new VmException(e);
				}
			}
		}

		Class<?> hc = hostClasses.get(ownerName);
		if (hc == null) {
			try {
				Class<?> aliased = vm.hostAliases.get(ownerName);
				hc = aliased != null
						? aliased : Class.forName(ownerName.replace('/', '.'));
			} catch (ClassNotFoundException e) {
				throw new VmError("host class missing " + ownerName);
			}
			hostClasses.put(ownerName, hc);
		}
		// guest receiver on a non-Object boundary: Throwable and Object.*
		// semantics are still VM-provided, not reflective
		if (kind == 0 && args.length > 0 && args[0] instanceof VmObject) {
			if (Throwable.class.isAssignableFrom(hc)) {
				Object tr = throwableBuiltin(t, (VmObject) args[0], name, args);
				if (tr != BuiltinMarker.NOT_HANDLED)
					return Vm.fromHost(tr);
			}
			Object or = objectBuiltin(t, name, desc, args);
			if (or != BuiltinMarker.NOT_HANDLED)
				return Vm.fromHost(or);
		}
		Object fr = fastBuiltin(t, ownerName, name, desc, args, kind);
		if (fr != BuiltinMarker.NOT_HANDLED)
			return Vm.fromHost(fr);
		try {
			if (kind == 3) {
				if (hc == String.class && args.length == 1 && args[0] instanceof VmObject)
					return new String(guestToString(t, (VmObject) args[0]));
				Constructor<?> c = findCtor(hc, desc);
				Class<?>[] pts = c.getParameterTypes();
				Object[] margs = new Object[pts.length];
				for (int i = 0; i < pts.length; i++)
					margs[i] = toHost(args[i], pts[i]);
				return c.newInstance(margs);
			}
			Method m = findMethod(hc, name, desc, kind == 1);
			Object recv = kind == 1 ? null : toHost(args[0], hc);
			Object[] margs = marshalArgs(m, args, kind == 1 ? 0 : 1);
			return Vm.fromHost(m.invoke(recv, margs), m.getReturnType().isPrimitive());
		} catch (InvocationTargetException e) {
			throw new VmException(e.getCause());
		} catch (IllegalAccessException | InstantiationException | IllegalArgumentException e) {
			throw new VmError("host call failed " + ownerName + "." + name + desc, e);
		}
	}

	enum BuiltinMarker {
		NOT_HANDLED
	}

	// ---------- java.lang.* builtins ----------

	private Object builtin(VmThread t, String owner, String name, String desc, Object[] args, int kind) {
		switch (owner) {
			case "java/lang/Object":
				// kind 3 is `new Object()` construction — no builtin, reflect it
				return kind == 3 ? BuiltinMarker.NOT_HANDLED
						: objectBuiltin(t, name, desc, args);
			case "java/lang/Class":
				return classBuiltin(t, name, desc, args, kind);
			case "java/lang/Thread":
				return threadBuiltin(t, name, desc, args, kind);
			case "java/lang/System":
				return systemBuiltin(name, desc, args);
			case "java/lang/String":
				if (name.equals("intern") && kind == 0)
					return vm.intern((String) args[0]);
				return BuiltinMarker.NOT_HANDLED;
			case "java/lang/reflect/Array":
				return arrayBuiltin(name, desc, args);
			default:
				return BuiltinMarker.NOT_HANDLED;
		}
	}

	private Object objectBuiltin(VmThread t, String name, String desc, Object[] args) {
		Object recv = args[0];
		switch (name) {
			case "<init>":
				return null;
			case "getClass":
				return classOf(recv);
			case "hashCode":
				return recv instanceof VmObject || recv instanceof GuestClass
						? System.identityHashCode(recv)
						: BuiltinMarker.NOT_HANDLED;
			case "equals":
				return recv instanceof VmObject
						? (Object) (recv == args[1])
						: BuiltinMarker.NOT_HANDLED;
			case "toString":
				return recv instanceof VmObject || recv instanceof GuestClass
						? recv.toString()
						: BuiltinMarker.NOT_HANDLED;
			case "clone": {
				if (!(recv instanceof VmObject))
					return BuiltinMarker.NOT_HANDLED;
				VmObject o = (VmObject) recv;
				if (!vm.resolveClass("java/lang/Cloneable").isInstance(o))
					throw new VmException(new CloneNotSupportedException());
				VmObject copy = new VmObject(o.clazz);
				System.arraycopy(o.fields, 0, copy.fields, 0, o.fields.length);
				return copy;
			}
			case "finalize":
				return null;
			case "wait": {
				long ms = desc.equals("()V") ? 0 : (Long) args[1];
				vm.objWait(t, recv, ms);
				return null;
			}
			case "notify":
				vm.objNotify(t, recv, false);
				return null;
			case "notifyAll":
				vm.objNotify(t, recv, true);
				return null;
			default:
				return BuiltinMarker.NOT_HANDLED;
		}
	}

	/**
	 * java.lang.Throwable methods on guest exceptions. The host-super <init>
	 * never ran reflectively (no shadow), so message/cause live in
	 * {@link VmObject#superCtorArgs}; the guest stack was captured at
	 * construction into {@link VmObject#exceptionTrace}.
	 */
	private Object throwableBuiltin(VmThread t, VmObject g, String name, Object[] args) {
		switch (name) {
			case "getMessage":
			case "getLocalizedMessage":
				return throwableMessage(g);
			case "getCause":
				return g.exceptionCause != null ? g.exceptionCause : ctorCause(g);
			case "initCause":
				if (ctorCause(g) != null)
					throw new VmException(
							new IllegalStateException("cause already initialized"));
				g.exceptionCause = args[1];
				return g;
			case "toString": {
				String msg = throwableMessage(g);
				return g.clazz.binaryName() + (msg != null ? ": " + msg : "");
			}
			case "printStackTrace": {
				String msg = throwableMessage(g);
				String text = g.clazz.binaryName() + (msg != null ? ": " + msg : "")
						+ (g.exceptionTrace != null ? g.exceptionTrace : "");
				if (args.length > 1 && args[1] != null) {
					try {
						args[1].getClass().getMethod("println", String.class)
								.invoke(args[1], text);
					} catch (Exception e) {
						System.err.println(text);
					}
				} else {
					System.err.println(text);
				}
				return null;
			}
			case "fillInStackTrace":
				g.exceptionTrace = Interpreter.trace(t);
				return g;
			default:
				return BuiltinMarker.NOT_HANDLED;
		}
	}

	private static String throwableMessage(VmObject g) {
		Object[] a = g.superCtorArgs;
		if (a != null)
			for (int i = 1; i < a.length; i++)
				if (a[i] instanceof String)
					return (String) a[i];
		return null;
	}

	private static Object ctorCause(VmObject g) {
		Object[] a = g.superCtorArgs;
		if (a != null)
			for (int i = 1; i < a.length; i++)
				if (a[i] instanceof Throwable || a[i] instanceof VmObject)
					return a[i];
		return null;
	}

	private Object classOf(Object recv) {
		if (recv instanceof VmObject)
			return ((VmObject) recv).clazz.guestClass();
		if (recv instanceof GuestClass)
			return realClass("java.lang.Class");
		return recv.getClass();
	}

	private Object realClass(String dotted) {
		try {
			return Class.forName(dotted);
		} catch (ClassNotFoundException e) {
			throw new VmError("missing " + dotted);
		}
	}

	private Object classBuiltin(VmThread t, String name, String desc, Object[] args, int kind) {
		Object recv = kind == 1 ? null : args[0];
		if (kind == 1 && name.equals("forName")) {
			String cn = ((String) args[0]).replace('.', '/');
			VmClass c = vm.classes.get(cn);
			if (c == null) {
				try {
					c = vm.resolveClass(cn);
				} catch (VmException e) {
					throw new VmException(new ClassNotFoundException(cn));
				}
			}
			return c.host ? (Object) c.hostClass : c.guestClass();
		}
		if (recv instanceof GuestClass) {
			VmClass c = ((GuestClass) recv).cls;
			switch (name) {
				case "getName": return c.binaryName();
				case "isInterface": return c.isInterface();
				case "isArray": return c.isArray();
				case "getSuperclass":
					return c.superClass == null ? null :
							c.superClass.host ? (Object) c.superClass.hostClass : c.superClass.guestClass();
				case "isInstance": return c.isInstance(args[1]);
				case "getResourceAsStream": {
					String path = (String) args[1];
					if (!path.startsWith("/"))
						path = "/" + packageOf(c.name) + path;
					return vm.getResource(path.substring(1));
				}
				case "newInstance": {
					vm.ensureInitBlocking(c, t);
					VmObject o = new VmObject(c);
					int depth = t.frames.size();
					t.nestedError = null;
					vm.pushInvoke(t, o, "<init>", "()V", new Object[]{o});
					Interpreter.execute(vm, t, Long.MAX_VALUE, depth);
					if (t.nestedError != null) {
						VmException ve = t.nestedError;
						t.nestedError = null;
						throw ve;
					}
					return o;
				}
				case "toString": return recv.toString();
				case "desiredAssertionStatus": return false;
			}
		}
		// host Class receiver — J2ME games commonly use
		// Runtime.getRuntime().getClass().getResourceAsStream() as a generic
		// jar-resource lookup; route it through the guest class sources first
		if (recv instanceof Class && name.equals("getResourceAsStream")) {
			String path = (String) args[1];
			if (!path.startsWith("/"))
				path = "/" + packageOf(((Class<?>) recv).getName().replace('.', '/')) + path;
			Object r = vm.getResource(path.substring(1));
			return r != null ? r : BuiltinMarker.NOT_HANDLED;
		}
		return BuiltinMarker.NOT_HANDLED;
	}

	private static String packageOf(String cls) {
		int i = cls.lastIndexOf('/');
		return i < 0 ? "" : cls.substring(0, i + 1);
	}

	private Object threadBuiltin(VmThread t, String name, String desc, Object[] args, int kind) {
		if (kind == 1 && name.equals("currentThread"))
			return hostThread(t);
		if (kind == 1 && name.equals("sleep")) {
			if (t.interrupted) {
				t.interrupted = false;
				throw new VmException(new InterruptedException());
			}
			vm.greenSleep(t, (Long) args[0]);
			return null;
		}
		if (kind == 1 && name.equals("yield"))
			return null;
		if (kind == 1 && name.equals("interrupted")) {
			// static: current thread's status, cleared on read
			boolean b = t.interrupted;
			t.interrupted = false;
			return b;
		}
		if (kind == 3) {
			// real Thread object (fields/identity intact), but its Runnable guest target
			// is recorded so start() can launch a green thread
			try {
				Constructor<?> c = findCtor(Thread.class, desc);
				Class<?>[] pts = c.getParameterTypes();
				Object[] margs = new Object[pts.length];
				for (int i = 0; i < pts.length; i++)
					margs[i] = toHost(args[i], pts[i]);
				Thread th = (Thread) c.newInstance(margs);
				// record the guest Runnable so start() can launch a green thread
				for (int i = 0; i < pts.length; i++) {
					if (pts[i] == Runnable.class && args[i] instanceof VmObject) {
						vm.greenTargets.put(th, args[i]);
						break;
					}
				}
				return th;
			} catch (InvocationTargetException e) {
				throw new VmException(e.getCause());
			} catch (Exception e) {
				throw new VmError("Thread ctor failed", e);
			}
		}
		Object recv = args[0];
		switch (name) {
			case "start":
				vm.startGreen(recv, t);
				return null;
			case "join": {
				if (t.interrupted) {
					t.interrupted = false;
					throw new VmException(new InterruptedException());
				}
				VmThread target = vm.greenOf(recv);
				if (target != null && target.isAlive()) {
					t.joinTarget = target;
					target.addJoinWaiter(t);
					vm.park(t, desc.equals("(J)V") ? (Long) args[1] : 0);
				}
				return null;
			}
			case "isAlive": {
				VmThread target = vm.greenOf(recv);
				return target != null && target.isAlive();
			}
			case "interrupt":
				vm.interruptGreen(vm.greenOf(recv));
				return null;
			case "isInterrupted": {
				VmThread target = vm.greenOf(recv);
				return target != null && target.interrupted;
			}
			// A guest `class X extends Thread` stays a VmObject with no host
			// Thread to reflect on — no-op the mutators rather than marshal-
			// crashing; the cooperative scheduler ignores priority anyway.
			case "setPriority":
			case "setDaemon":
			case "setName":
				return recv instanceof VmObject ? null : BuiltinMarker.NOT_HANDLED;
			case "getPriority":
				return recv instanceof VmObject ? 5 : BuiltinMarker.NOT_HANDLED;
			case "getName":
				return recv instanceof VmObject ? recv.toString() : BuiltinMarker.NOT_HANDLED;
			case "isDaemon":
				return recv instanceof VmObject ? false : BuiltinMarker.NOT_HANDLED;
		}
		return BuiltinMarker.NOT_HANDLED;
	}

	private Thread hostThread(VmThread t) {
		if (t.hostThread == null)
			t.hostThread = new Thread();
		return t.hostThread;
	}

	private Object systemBuiltin(String name, String desc, Object[] args) {
		switch (name) {
			case "currentTimeMillis": return System.currentTimeMillis();
			case "nanoTime": return System.nanoTime();
			case "arraycopy":
				System.arraycopy(args[0], (Integer) args[1], args[2], (Integer) args[3], (Integer) args[4]);
				return null;
			case "identityHashCode": return System.identityHashCode(args[0]);
			case "gc": return null;
			case "exit": throw new VmException(new SecurityException("guest called System.exit"));
			case "getProperty":
				return vm.j2meProps.getProperty((String) args[0],
						args.length > 1 ? (String) args[1] : null);
			case "getProperties": return vm.j2meProps;
			case "lineSeparator": return "\n";
		}
		return BuiltinMarker.NOT_HANDLED;
	}

	// ---------- hot-path builtins (direct calls, no reflection) ----------
	// J2ME engines iterate Vector/Hashtable and pound Graphics/Image/Font every
	// frame; reflective dispatch costs ~5-10us per call on-device, which turns
	// into ~30ms/frame on list-heavy games. These direct casts bypass it.
	// Every method returns NOT_HANDLED for signatures it doesn't cover so the
	// reflective path stays the fallback.

	private static Object NOT() {
		return BuiltinMarker.NOT_HANDLED;
	}

	/** Run the guest's own toString() on a VmObject (needed when host APIs like
	 *  String.valueOf / String(CharSequence) receive guest objects). */
	private String guestToString(VmThread t, VmObject o) {
		int depth = t.frames.size();
		t.nestedError = null;
		vm.pushInvoke(t, o, "toString", "()Ljava/lang/String;", new Object[]{o});
		Interpreter.execute(vm, t, Long.MAX_VALUE, depth);
		if (t.nestedError != null) {
			VmException ve = t.nestedError;
			t.nestedError = null;
			throw ve;
		}
		Object r = t.nestedResult;
		return r == null ? null : (String) r;
	}

	private static boolean anyGuest(Object[] a) {
		for (Object o : a)
			if (o instanceof VmObject)
				return true;
		return false;
	}

	private Object fastBuiltin(VmThread t, String owner, String name, String desc, Object[] args, int kind) {
		// ctors carry no receiver — args[] may be empty
		if (kind == 3)
			return NOT();
		// instance methods with a guest receiver must marshal (shadow/error)
		if (kind != 1 && args.length > 0 && args[0] instanceof VmObject)
			return NOT();
		try {
			switch (owner) {
				case "java/util/Vector": return vectorBuiltin(name, desc, args, kind);
				case "java/util/Stack": return stackBuiltin(name, desc, args, kind);
				case "java/util/Hashtable": return tableBuiltin(name, desc, args, kind);
				case "java/util/Enumeration": return enumBuiltin(name, args);
				case "java/util/Random": return randomBuiltin(name, desc, args, kind);
				case "java/lang/String": return stringBuiltin(t, name, desc, args, kind);
				case "java/lang/StringBuffer": return sbBuiltin(name, desc, args);
				case "java/lang/StringBuilder": return sbBuiltin(name, desc, args);
				case "java/lang/Math": return mathBuiltin(name, desc, args);
				case "java/lang/Integer": return intBuiltin(name, desc, args, kind);
				case "java/lang/Long": return longBuiltin(name, desc, args, kind);
				case "java/lang/Float": return floatBuiltin(name, desc, args, kind);
				case "java/lang/Double": return doubleBuiltin(name, desc, args, kind);
				case "java/io/InputStream":
				case "java/io/ByteArrayInputStream":
				case "java/io/FilterInputStream":
				case "java/io/BufferedInputStream":
					return isBuiltin(name, desc, args);
				case "java/io/DataInputStream": return disBuiltin(name, desc, args);
				default: return NOT();
			}
		} catch (VmError | VmException e) {
			throw e;
		} catch (Exception | LinkageError | AssertionError e) {
			throw new VmException(e);
		}
	}

	private static Object vectorBuiltin(String name, String desc, Object[] args, int kind) {
		java.util.Vector<Object> v = (java.util.Vector<Object>) args[0];
		switch (name) {
			case "size": return v.size();
			case "isEmpty": return v.isEmpty() ? 1 : 0;
			case "capacity": return v.capacity();
			case "elementAt": return v.elementAt((Integer) args[1]);
			case "firstElement": return v.firstElement();
			case "lastElement": return v.lastElement();
			case "get": return v.get((Integer) args[1]);
			case "set": return v.set((Integer) args[1], args[2]);
			case "elements": return v.elements();
			case "indexOf":
				return desc.equals("(Ljava/lang/Object;I)I")
						? v.indexOf(args[1], (Integer) args[2])
						: v.indexOf(args[1]);
			case "lastIndexOf":
				return desc.equals("(Ljava/lang/Object;I)I")
						? v.lastIndexOf(args[1], (Integer) args[2])
						: v.lastIndexOf(args[1]);
			case "contains": return v.contains(args[1]) ? 1 : 0;
			case "containsAll": return v.containsAll((java.util.Collection<?>) args[1]) ? 1 : 0;
			case "addElement": v.addElement(args[1]); return null;
			case "add":
				if (desc.equals("(ILjava/lang/Object;)V")) {
					v.add((Integer) args[1], args[2]);
					return null;
				}
				return v.add(args[1]) ? 1 : 0;
			case "addAll": return v.addAll((java.util.Collection<?>) args[1]) ? 1 : 0;
			case "insertElementAt": v.insertElementAt(args[1], (Integer) args[2]); return null;
			case "setElementAt": v.setElementAt(args[1], (Integer) args[2]); return null;
			case "removeElement": return v.removeElement(args[1]) ? 1 : 0;
			case "removeElementAt": v.removeElementAt((Integer) args[1]); return null;
			case "removeAllElements": v.removeAllElements(); return null;
			case "remove":
				if (desc.equals("(I)Ljava/lang/Object;"))
					return v.remove((int) (Integer) args[1]);
				return v.remove(args[1]) ? 1 : 0;
			case "clear": v.clear(); return null;
			case "setSize": v.setSize((Integer) args[1]); return null;
			case "ensureCapacity": v.ensureCapacity((Integer) args[1]); return null;
			case "trimToSize": v.trimToSize(); return null;
			case "copyInto": v.copyInto((Object[]) args[1]); return null;
			case "toArray": return v.toArray();
			case "iterator": return v.iterator();
			case "toString": return v.toString();
			case "hashCode": return v.hashCode();
			case "equals": return v.equals(args[1]) ? 1 : 0;
			case "clone": return v.clone();
			default: return NOT();
		}
	}

	private static Object stackBuiltin(String name, String desc, Object[] args, int kind) {
		java.util.Stack<Object> s = (java.util.Stack<Object>) args[0];
		switch (name) {
			case "push": return s.push(args[1]);
			case "pop": return s.pop();
			case "peek": return s.peek();
			case "empty": return s.empty() ? 1 : 0;
			case "search": return s.search(args[1]);
			default:
				// inherited Vector members called with owner=Stack
				return vectorBuiltin(name, desc, args, kind);
		}
	}

	private static Object tableBuiltin(String name, String desc, Object[] args, int kind) {
		java.util.Hashtable<Object, Object> t = (java.util.Hashtable<Object, Object>) args[0];
		switch (name) {
			case "size": return t.size();
			case "isEmpty": return t.isEmpty() ? 1 : 0;
			case "get": return t.get(args[1]);
			case "getOrDefault": return t.getOrDefault(args[1], args[2]);
			case "put": return t.put(args[1], args[2]);
			case "remove": return t.remove(args[1]);
			case "containsKey": return t.containsKey(args[1]) ? 1 : 0;
			case "contains":
			case "containsValue": return t.contains(args[1]) ? 1 : 0;
			case "keys": return t.keys();
			case "elements": return t.elements();
			case "keySet": return t.keySet();
			case "clear": t.clear(); return null;
			case "toString": return t.toString();
			default: return NOT();
		}
	}

	private static Object enumBuiltin(String name, Object[] args) {
		java.util.Enumeration<?> e = (java.util.Enumeration<?>) args[0];
		switch (name) {
			case "hasMoreElements": return e.hasMoreElements() ? 1 : 0;
			case "nextElement": return e.nextElement();
			default: return NOT();
		}
	}

	private static Object randomBuiltin(String name, String desc, Object[] args, int kind) {
		java.util.Random r = (java.util.Random) args[0];
		switch (name) {
			case "nextInt":
				return desc.equals("(I)I") ? r.nextInt((Integer) args[1]) : r.nextInt();
			case "nextLong": return r.nextLong();
			case "nextBoolean": return r.nextBoolean() ? 1 : 0;
			case "nextFloat": return r.nextFloat();
			case "nextDouble": return r.nextDouble();
			case "nextGaussian": return r.nextGaussian();
			case "nextBytes": r.nextBytes((byte[]) args[1]); return null;
			case "setSeed": r.setSeed((Long) args[1]); return null;
			default: return NOT();
		}
	}

	private Object stringBuiltin(VmThread t, String name, String desc, Object[] args, int kind) {
		if (kind == 1) { // statics
			if (!name.equals("valueOf"))
				return NOT();
			switch (desc) {
				case "(I)Ljava/lang/String;": return String.valueOf((Integer) args[0]);
				case "(J)Ljava/lang/String;": return String.valueOf((Long) args[0]);
				case "(Z)Ljava/lang/String;": return String.valueOf(((Integer) args[0]) != 0);
				case "(C)Ljava/lang/String;": return String.valueOf((char) (int) (Integer) args[0]);
				case "(F)Ljava/lang/String;": return String.valueOf((Float) args[0]);
				case "(D)Ljava/lang/String;": return String.valueOf((Double) args[0]);
				case "([C)Ljava/lang/String;": return String.valueOf((char[]) args[0]);
				case "([CII)Ljava/lang/String;":
					return String.valueOf((char[]) args[0], (Integer) args[1], (Integer) args[2]);
				case "(Ljava/lang/Object;)Ljava/lang/String;":
					return args[0] instanceof VmObject
							? guestToString(t, (VmObject) args[0])
							: String.valueOf(args[0]);
				default: return NOT();
			}
		}
		String s = (String) args[0];
		switch (name) {
			case "length": return s.length();
			case "isEmpty": return s.isEmpty() ? 1 : 0;
			case "charAt": return (int) s.charAt((Integer) args[1]);
			case "codePointAt": return s.codePointAt((Integer) args[1]);
			case "substring":
				return desc.equals("(I)Ljava/lang/String;")
						? s.substring((Integer) args[1])
						: s.substring((Integer) args[1], (Integer) args[2]);
			case "indexOf":
				switch (desc) {
					case "(I)I": return s.indexOf((Integer) args[1]);
					case "(II)I": return s.indexOf((Integer) args[1], (Integer) args[2]);
					case "(Ljava/lang/String;)I": return s.indexOf((String) args[1]);
					case "(Ljava/lang/String;I)I":
						return s.indexOf((String) args[1], (Integer) args[2]);
					default: return NOT();
				}
			case "lastIndexOf":
				switch (desc) {
					case "(I)I": return s.lastIndexOf((Integer) args[1]);
					case "(II)I": return s.lastIndexOf((Integer) args[1], (Integer) args[2]);
					case "(Ljava/lang/String;)I": return s.lastIndexOf((String) args[1]);
					case "(Ljava/lang/String;I)I":
						return s.lastIndexOf((String) args[1], (Integer) args[2]);
					default: return NOT();
				}
			case "startsWith":
				return desc.equals("(Ljava/lang/String;)Z")
						? (s.startsWith((String) args[1]) ? 1 : 0)
						: (s.startsWith((String) args[1], (Integer) args[2]) ? 1 : 0);
			case "endsWith": return s.endsWith((String) args[1]) ? 1 : 0;
			case "contains": return s.contains((CharSequence) args[1]) ? 1 : 0;
			case "equals": return s.equals(args[1]) ? 1 : 0;
			case "equalsIgnoreCase": return s.equalsIgnoreCase((String) args[1]) ? 1 : 0;
			case "compareTo": return s.compareTo((String) args[1]);
			case "compareToIgnoreCase": return s.compareToIgnoreCase((String) args[1]);
			case "contentEquals": return s.contentEquals((CharSequence) args[1]) ? 1 : 0;
			case "concat": return s.concat((String) args[1]);
			case "toUpperCase":
				return desc.equals("()Ljava/lang/String;") ? s.toUpperCase() : NOT();
			case "toLowerCase":
				return desc.equals("()Ljava/lang/String;") ? s.toLowerCase() : NOT();
			case "trim": return s.trim();
			case "toCharArray": return s.toCharArray();
			case "getChars":
				s.getChars((Integer) args[1], (Integer) args[2], (char[]) args[3],
						(Integer) args[4]);
				return null;
			case "getBytes":
				return desc.equals("()[B") ? s.getBytes() : NOT();
			case "replace":
				switch (desc) {
					case "(CC)Ljava/lang/String;":
						return s.replace((char) (int) (Integer) args[1],
								(char) (int) (Integer) args[2]);
					case "(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;":
						return s.replace((CharSequence) args[1], (CharSequence) args[2]);
					default: return NOT();
				}
			case "split": return s.split((String) args[1]);
			case "toString": return s;
			case "hashCode": return s.hashCode();
			default: return NOT();
		}
	}

	private static Object sbBuiltin(String name, String desc, Object[] args) {
		Object r = args[0];
		if (r instanceof StringBuilder)
			return sbBld((StringBuilder) r, name, desc, args);
		if (r instanceof StringBuffer)
			return sbBuf((StringBuffer) r, name, desc, args);
		return NOT();
	}

	private static Object sbBld(StringBuilder sb, String name, String desc, Object[] args) {
		switch (name) {
			case "append":
				switch (desc) {
					case "(Ljava/lang/String;)Ljava/lang/StringBuffer;":
					case "(Ljava/lang/String;)Ljava/lang/StringBuilder;":
						return sb.append((String) args[1]);
					case "(I)Ljava/lang/StringBuffer;":
					case "(I)Ljava/lang/StringBuilder;":
						return sb.append((int) (Integer) args[1]);
					case "(J)Ljava/lang/StringBuffer;":
					case "(J)Ljava/lang/StringBuilder;":
						return sb.append((long) (Long) args[1]);
					case "(Z)Ljava/lang/StringBuffer;":
					case "(Z)Ljava/lang/StringBuilder;":
						return sb.append(((Integer) args[1]) != 0);
					case "(C)Ljava/lang/StringBuffer;":
					case "(C)Ljava/lang/StringBuilder;":
						return sb.append((char) (int) (Integer) args[1]);
					case "(F)Ljava/lang/StringBuffer;":
					case "(F)Ljava/lang/StringBuilder;":
						return sb.append((float) (Float) args[1]);
					case "(D)Ljava/lang/StringBuffer;":
					case "(D)Ljava/lang/StringBuilder;":
						return sb.append((double) (Double) args[1]);
					case "(Ljava/lang/Object;)Ljava/lang/StringBuffer;":
					case "(Ljava/lang/Object;)Ljava/lang/StringBuilder;":
						return args[1] instanceof VmObject ? NOT() : sb.append(args[1]);
					case "([C)Ljava/lang/StringBuffer;":
					case "([C)Ljava/lang/StringBuilder;":
						return sb.append((char[]) args[1]);
					case "([CII)Ljava/lang/StringBuffer;":
					case "([CII)Ljava/lang/StringBuilder;":
						return sb.append((char[]) args[1], (Integer) args[2],
								(Integer) args[3]);
					default: return NOT();
				}
			case "insert":
				switch (desc) {
					case "(ILjava/lang/String;)Ljava/lang/StringBuffer;":
					case "(ILjava/lang/String;)Ljava/lang/StringBuilder;":
						return sb.insert((int) (Integer) args[1], (String) args[2]);
					case "(II)Ljava/lang/StringBuffer;":
					case "(II)Ljava/lang/StringBuilder;":
						return sb.insert((int) (Integer) args[1], (int) (Integer) args[2]);
					case "(IJ)Ljava/lang/StringBuffer;":
					case "(IJ)Ljava/lang/StringBuilder;":
						return sb.insert((int) (Integer) args[1], (long) (Long) args[2]);
					case "(IC)Ljava/lang/StringBuffer;":
					case "(IC)Ljava/lang/StringBuilder;":
						return sb.insert((int) (Integer) args[1], (char) (int) (Integer) args[2]);
					case "(ILjava/lang/Object;)Ljava/lang/StringBuffer;":
					case "(ILjava/lang/Object;)Ljava/lang/StringBuilder;":
						return args[2] instanceof VmObject ? NOT()
								: sb.insert((int) (Integer) args[1], args[2]);
					default: return NOT();
				}
			case "toString": return sb.toString();
			case "length": return sb.length();
			case "capacity": return sb.capacity();
			case "charAt": return (int) sb.charAt((Integer) args[1]);
			case "setCharAt": sb.setCharAt((Integer) args[1], (char) (int) (Integer) args[2]); return null;
			case "delete": return sb.delete((Integer) args[1], (Integer) args[2]);
			case "deleteCharAt": return sb.deleteCharAt((Integer) args[1]);
			case "reverse": return sb.reverse();
			case "setLength": sb.setLength((Integer) args[1]); return null;
			case "ensureCapacity": sb.ensureCapacity((Integer) args[1]); return null;
			case "substring": return sb.substring((Integer) args[1]);
			case "getChars":
				sb.getChars((Integer) args[1], (Integer) args[2], (char[]) args[3],
						(Integer) args[4]);
				return null;
			default: return NOT();
		}
	}

	private static Object sbBuf(StringBuffer sb, String name, String desc, Object[] args) {
		switch (name) {
			case "append":
				switch (desc) {
					case "(Ljava/lang/String;)Ljava/lang/StringBuffer;":
					case "(Ljava/lang/String;)Ljava/lang/StringBuilder;":
						return sb.append((String) args[1]);
					case "(I)Ljava/lang/StringBuffer;":
					case "(I)Ljava/lang/StringBuilder;":
						return sb.append((int) (Integer) args[1]);
					case "(J)Ljava/lang/StringBuffer;":
					case "(J)Ljava/lang/StringBuilder;":
						return sb.append((long) (Long) args[1]);
					case "(Z)Ljava/lang/StringBuffer;":
					case "(Z)Ljava/lang/StringBuilder;":
						return sb.append(((Integer) args[1]) != 0);
					case "(C)Ljava/lang/StringBuffer;":
					case "(C)Ljava/lang/StringBuilder;":
						return sb.append((char) (int) (Integer) args[1]);
					case "(F)Ljava/lang/StringBuffer;":
					case "(F)Ljava/lang/StringBuilder;":
						return sb.append((float) (Float) args[1]);
					case "(D)Ljava/lang/StringBuffer;":
					case "(D)Ljava/lang/StringBuilder;":
						return sb.append((double) (Double) args[1]);
					case "(Ljava/lang/Object;)Ljava/lang/StringBuffer;":
					case "(Ljava/lang/Object;)Ljava/lang/StringBuilder;":
						return args[1] instanceof VmObject ? NOT() : sb.append(args[1]);
					case "([C)Ljava/lang/StringBuffer;":
					case "([C)Ljava/lang/StringBuilder;":
						return sb.append((char[]) args[1]);
					case "([CII)Ljava/lang/StringBuffer;":
					case "([CII)Ljava/lang/StringBuilder;":
						return sb.append((char[]) args[1], (Integer) args[2],
								(Integer) args[3]);
					default: return NOT();
				}
			case "insert":
				switch (desc) {
					case "(ILjava/lang/String;)Ljava/lang/StringBuffer;":
					case "(ILjava/lang/String;)Ljava/lang/StringBuilder;":
						return sb.insert((int) (Integer) args[1], (String) args[2]);
					case "(II)Ljava/lang/StringBuffer;":
					case "(II)Ljava/lang/StringBuilder;":
						return sb.insert((int) (Integer) args[1], (int) (Integer) args[2]);
					case "(IJ)Ljava/lang/StringBuffer;":
					case "(IJ)Ljava/lang/StringBuilder;":
						return sb.insert((int) (Integer) args[1], (long) (Long) args[2]);
					case "(IC)Ljava/lang/StringBuffer;":
					case "(IC)Ljava/lang/StringBuilder;":
						return sb.insert((int) (Integer) args[1], (char) (int) (Integer) args[2]);
					case "(ILjava/lang/Object;)Ljava/lang/StringBuffer;":
					case "(ILjava/lang/Object;)Ljava/lang/StringBuilder;":
						return args[2] instanceof VmObject ? NOT()
								: sb.insert((int) (Integer) args[1], args[2]);
					default: return NOT();
				}
			case "toString": return sb.toString();
			case "length": return sb.length();
			case "capacity": return sb.capacity();
			case "charAt": return (int) sb.charAt((Integer) args[1]);
			case "setCharAt": sb.setCharAt((Integer) args[1], (char) (int) (Integer) args[2]); return null;
			case "delete": return sb.delete((Integer) args[1], (Integer) args[2]);
			case "deleteCharAt": return sb.deleteCharAt((Integer) args[1]);
			case "reverse": return sb.reverse();
			case "setLength": sb.setLength((Integer) args[1]); return null;
			case "ensureCapacity": sb.ensureCapacity((Integer) args[1]); return null;
			case "substring": return sb.substring((Integer) args[1]);
			case "getChars":
				sb.getChars((Integer) args[1], (Integer) args[2], (char[]) args[3],
						(Integer) args[4]);
				return null;
			default: return NOT();
		}
	}

	private static Object mathBuiltin(String name, String desc, Object[] args) {
		switch (name) {
			case "abs":
				switch (desc) {
					case "(I)I": return Math.abs((Integer) args[0]);
					case "(J)J": return Math.abs((Long) args[0]);
					case "(F)F": return Math.abs((Float) args[0]);
					case "(D)D": return Math.abs((Double) args[0]);
					default: return NOT();
				}
			case "min":
				switch (desc) {
					case "(II)I": return Math.min((Integer) args[0], (Integer) args[1]);
					case "(JJ)J": return Math.min((Long) args[0], (Long) args[1]);
					case "(FF)F": return Math.min((Float) args[0], (Float) args[1]);
					case "(DD)D": return Math.min((Double) args[0], (Double) args[1]);
					default: return NOT();
				}
			case "max":
				switch (desc) {
					case "(II)I": return Math.max((Integer) args[0], (Integer) args[1]);
					case "(JJ)J": return Math.max((Long) args[0], (Long) args[1]);
					case "(FF)F": return Math.max((Float) args[0], (Float) args[1]);
					case "(DD)D": return Math.max((Double) args[0], (Double) args[1]);
					default: return NOT();
				}
			case "random": return Math.random();
			case "sqrt": return Math.sqrt((Double) args[0]);
			case "sin": return Math.sin((Double) args[0]);
			case "cos": return Math.cos((Double) args[0]);
			case "tan": return Math.tan((Double) args[0]);
			case "asin": return Math.asin((Double) args[0]);
			case "acos": return Math.acos((Double) args[0]);
			case "atan": return Math.atan((Double) args[0]);
			case "atan2": return Math.atan2((Double) args[0], (Double) args[1]);
			case "floor": return Math.floor((Double) args[0]);
			case "ceil": return Math.ceil((Double) args[0]);
			case "round":
				return desc.equals("(F)I") ? Math.round((Float) args[0])
						: Math.round((Double) args[0]);
			case "pow": return Math.pow((Double) args[0], (Double) args[1]);
			case "exp": return Math.exp((Double) args[0]);
			case "log": return Math.log((Double) args[0]);
			case "toRadians": return Math.toRadians((Double) args[0]);
			case "toDegrees": return Math.toDegrees((Double) args[0]);
			default: return NOT();
		}
	}

	private static Object intBuiltin(String name, String desc, Object[] args, int kind) {
		if (kind == 1) {
			switch (name) {
				case "parseInt":
					return desc.equals("(Ljava/lang/String;)I")
							? Integer.parseInt((String) args[0])
							: Integer.parseInt((String) args[0], (Integer) args[1]);
				case "toString":
					return desc.equals("(I)Ljava/lang/String;")
							? Integer.toString((Integer) args[0])
							: desc.equals("(II)Ljava/lang/String;")
							? Integer.toString((Integer) args[0], (Integer) args[1])
							: NOT();
				case "toHexString": return Integer.toHexString((Integer) args[0]);
				case "toOctalString": return Integer.toOctalString((Integer) args[0]);
				case "toBinaryString": return Integer.toBinaryString((Integer) args[0]);
				case "valueOf":
					return desc.equals("(I)Ljava/lang/Integer;")
							? (Integer) args[0] : NOT();
				default: return NOT();
			}
		}
		Integer i = (Integer) args[0];
		switch (name) {
			case "intValue": return i;
			case "byteValue": return i.byteValue();
			case "shortValue": return i.shortValue();
			case "longValue": return i.longValue();
			case "floatValue": return i.floatValue();
			case "doubleValue": return i.doubleValue();
			case "toString": return i.toString();
			case "hashCode": return i;
			case "equals": return i.equals(args[1]) ? 1 : 0;
			case "compareTo": return i.compareTo((Integer) args[1]);
			default: return NOT();
		}
	}

	private static Object longBuiltin(String name, String desc, Object[] args, int kind) {
		if (kind == 1) {
			switch (name) {
				case "parseLong":
					return desc.equals("(Ljava/lang/String;)J")
							? Long.parseLong((String) args[0])
							: Long.parseLong((String) args[0], (Integer) args[1]);
				case "toString":
					return desc.equals("(J)Ljava/lang/String;")
							? Long.toString((Long) args[0])
							: desc.equals("(JI)Ljava/lang/String;")
							? Long.toString((Long) args[0], (Integer) args[1])
							: NOT();
				case "toHexString": return Long.toHexString((Long) args[0]);
				case "valueOf":
					return desc.equals("(J)Ljava/lang/Long;") ? (Long) args[0] : NOT();
				default: return NOT();
			}
		}
		Long l = (Long) args[0];
		switch (name) {
			case "intValue": return l.intValue();
			case "longValue": return l;
			case "floatValue": return l.floatValue();
			case "doubleValue": return l.doubleValue();
			case "byteValue": return l.byteValue();
			case "shortValue": return l.shortValue();
			case "toString": return l.toString();
			case "hashCode": return l.hashCode();
			case "equals": return l.equals(args[1]) ? 1 : 0;
			case "compareTo": return l.compareTo((Long) args[1]);
			default: return NOT();
		}
	}

	private static Object floatBuiltin(String name, String desc, Object[] args, int kind) {
		if (kind == 1) {
			switch (name) {
				case "floatToIntBits": return Float.floatToIntBits((Float) args[0]);
				case "intBitsToFloat": return Float.intBitsToFloat((Integer) args[0]);
				case "isNaN": return Float.isNaN((Float) args[0]) ? 1 : 0;
				case "toString": return Float.toString((Float) args[0]);
				case "parseFloat": return Float.parseFloat((String) args[0]);
				default: return NOT();
			}
		}
		Float f = (Float) args[0];
		switch (name) {
			case "intValue": return f.intValue();
			case "longValue": return f.longValue();
			case "floatValue": return f;
			case "doubleValue": return f.doubleValue();
			case "isNaN": return f.isNaN() ? 1 : 0;
			case "toString": return f.toString();
			case "equals": return f.equals(args[1]) ? 1 : 0;
			default: return NOT();
		}
	}

	private static Object doubleBuiltin(String name, String desc, Object[] args, int kind) {
		if (kind == 1) {
			switch (name) {
				case "doubleToLongBits": return Double.doubleToLongBits((Double) args[0]);
				case "longBitsToDouble": return Double.longBitsToDouble((Long) args[0]);
				case "isNaN": return Double.isNaN((Double) args[0]) ? 1 : 0;
				case "toString": return Double.toString((Double) args[0]);
				case "parseDouble": return Double.parseDouble((String) args[0]);
				default: return NOT();
			}
		}
		Double d = (Double) args[0];
		switch (name) {
			case "intValue": return d.intValue();
			case "longValue": return d.longValue();
			case "floatValue": return d.floatValue();
			case "doubleValue": return d;
			case "isNaN": return d.isNaN() ? 1 : 0;
			case "toString": return d.toString();
			case "equals": return d.equals(args[1]) ? 1 : 0;
			default: return NOT();
		}
	}

	private static Object isBuiltin(String name, String desc, Object[] args)
			throws java.io.IOException {
		java.io.InputStream in = (java.io.InputStream) args[0];
		switch (name) {
			case "read":
				if (desc.equals("()I")) return in.read();
				if (desc.equals("([B)I")) return in.read((byte[]) args[1]);
				return in.read((byte[]) args[1], (Integer) args[2], (Integer) args[3]);
			case "skip": return in.skip((Long) args[1]);
			case "available": return in.available();
			case "close": in.close(); return null;
			case "mark": in.mark((Integer) args[1]); return null;
			case "reset": in.reset(); return null;
			case "markSupported": return in.markSupported() ? 1 : 0;
			default: return NOT();
		}
	}

	private static Object disBuiltin(String name, String desc, Object[] args)
			throws java.io.IOException {
		java.io.DataInputStream d = (java.io.DataInputStream) args[0];
		switch (name) {
			case "read":
				if (desc.equals("()I")) return d.read();
				if (desc.equals("([B)I")) return d.read((byte[]) args[1]);
				return d.read((byte[]) args[1], (Integer) args[2], (Integer) args[3]);
			case "readInt": return d.readInt();
			case "readShort": return (int) d.readShort();
			case "readByte": return (int) d.readByte();
			case "readChar": return (int) d.readChar();
			case "readLong": return d.readLong();
			case "readFloat": return d.readFloat();
			case "readDouble": return d.readDouble();
			case "readBoolean": return d.readBoolean() ? 1 : 0;
			case "readUnsignedByte": return d.readUnsignedByte();
			case "readUnsignedShort": return d.readUnsignedShort();
			case "readUTF": return d.readUTF();
			case "readLine": return d.readLine();
			case "readFully":
				if (desc.equals("([B)V")) d.readFully((byte[]) args[1]);
				else d.readFully((byte[]) args[1], (Integer) args[2], (Integer) args[3]);
				return null;
			case "skipBytes": return d.skipBytes((Integer) args[1]);
			case "skip": return d.skip((Long) args[1]);
			case "available": return d.available();
			case "close": d.close(); return null;
			case "mark": d.mark((Integer) args[1]); return null;
			case "reset": d.reset(); return null;
			case "markSupported": return d.markSupported() ? 1 : 0;
			default: return NOT();
		}
	}

	// ---------- field access (used by Interpreter) ----------

	Object getStaticField(VmClass owner, ClassFile.Ref r) {
		Field f = findField(owner.hostClass, r.name, r.desc, true);
		try {
			return Vm.fromHost(f.get(null), f.getType().isPrimitive());
		} catch (IllegalAccessException e) {
			throw new VmError("getfield " + r.name, e);
		}
	}

	void putStaticField(VmClass owner, ClassFile.Ref r, Object v) {
		Field f = findField(owner.hostClass, r.name, r.desc, true);
		try {
			f.set(null, toHost(v, f.getType()));
		} catch (IllegalAccessException e) {
			throw new VmError("putfield " + r.name, e);
		}
	}

	Object getField(Object recv, ClassFile.Ref r) {
		Object target = recv instanceof VmObject ? ((VmObject) recv).hostShadow : recv;
		if (target == null)
			throw new VmException(new NoSuchFieldError(r.name + " (no host shadow)"));
		Field f = findField(target.getClass(), r.name, r.desc, false);
		try {
			return Vm.fromHost(f.get(target), f.getType().isPrimitive());
		} catch (IllegalAccessException e) {
			throw new VmError("getfield " + r.name, e);
		}
	}

	void putField(Object recv, ClassFile.Ref r, Object v) {
		Object target = recv instanceof VmObject ? ((VmObject) recv).hostShadow : recv;
		if (target == null)
			throw new VmException(new NoSuchFieldError(r.name + " (no host shadow)"));
		Field f = findField(target.getClass(), r.name, r.desc, false);
		try {
			f.set(target, toHost(v, f.getType()));
		} catch (IllegalAccessException e) {
			throw new VmError("putfield " + r.name, e);
		}
	}

	private Object arrayBuiltin(String name, String desc, Object[] args) {
		if (name.equals("newInstance")) {
			Class<?> comp = args[0] instanceof GuestClass
					? Object.class : (Class<?>) args[0];
			int n = args.length == 2 ? (Integer) args[1] : -1;
			if (n >= 0)
				return Array.newInstance(comp, n);
			int[] dims = new int[Array.getLength(args[1])];
			for (int i = 0; i < dims.length; i++)
				dims[i] = (Integer) Array.get(args[1], i);
			return Array.newInstance(comp, dims);
		}
		if (name.equals("getLength"))
			return Array.getLength(args[0]);
		return BuiltinMarker.NOT_HANDLED;
	}
}
