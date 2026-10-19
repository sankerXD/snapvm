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

	private Object proxyFor(final VmObject guest, final Class<?> iface) {
		Object p = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
				new InvocationHandler() {
					public Object invoke(Object proxy, Method method, Object[] args) {
						Object[] in = args == null ? new Object[0] : args.clone();
						for (int i = 0; i < in.length; i++)
							in[i] = Vm.fromHost(in[i]);
						Object r = vm.callFromHost(guest, method.getName(),
								descOf(method), in);
						return toHost(r, method.getReturnType());
					}
				});
		vm.proxies.put(p, guest);
		return p;
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
		// VM-provided semantics first
		Object r = builtin(t, ownerName, name, desc, args, kind);
		if (r != BuiltinMarker.NOT_HANDLED)
			return Vm.fromHost(r);
		// adapter-registered blocking calls park the green thread until done
		Vm.ParkedCall pc = vm.parkedCall(ownerName, name, desc);
		if (pc != null)
			return vm.callParked(t, pc, args);

		Class<?> hc;
		try {
			hc = Class.forName(ownerName.replace('/', '.'));
		} catch (ClassNotFoundException e) {
			throw new VmError("host class missing " + ownerName);
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
		try {
			if (kind == 3) {
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
			return Vm.fromHost(m.invoke(recv, margs));
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
					vm.pushInvoke(t, o, "<init>", "()V", new Object[]{o});
					Interpreter.execute(vm, t, Long.MAX_VALUE, depth);
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
			vm.greenSleep(t, (Long) args[0]);
			return null;
		}
		if (kind == 1 && name.equals("yield"))
			return null;
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
				VmThread target = vm.greenOf(recv);
				if (target != null && target.isAlive()) {
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
				return null;
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

	// ---------- field access (used by Interpreter) ----------

	Object getStaticField(VmClass owner, ClassFile.Ref r) {
		Field f = findField(owner.hostClass, r.name, r.desc, true);
		try {
			return Vm.fromHost(f.get(null));
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
			return Vm.fromHost(f.get(target));
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
