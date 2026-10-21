package org.snapvm;

import java.io.ByteArrayInputStream;
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
	/** Dead frames available for reuse — avoids locals/stack array churn per call. */
	// pooled frames bucketed by exact array sizes — call/return pairs of the
	// same methods reuse with a single map hit instead of a linear fit-scan
	final java.util.Map<Integer, java.util.ArrayDeque<Frame>> framePools = new java.util.HashMap<>();
	final Map<String, String> interned = new HashMap<>();
	final IdentityHashMap<Object, Monitor> monitors = new IdentityHashMap<>();
	final IdentityHashMap<Object, Object> proxies = new IdentityHashMap<>();   // proxy -> guest obj
	/** serial -> live guest object; lets pre-restore host proxies reach the revived object. */
	final Map<Long, VmObject> liveBySerial = new HashMap<>();
	private int dropLogCount;
	private long nextSerial = 1; // monotonic across restores; never reset
	long nextThreadSerial = 1;   // thread identity across restores; never reset
	private int threadCreates;   // since-last-sweep counter for corpse culling
	final IdentityHashMap<Thread, Object> greenTargets = new IdentityHashMap<>(); // host Thread -> guest Runnable
	final IdentityHashMap<Object, VmClass> guestArrayTypes = new IdentityHashMap<>();
	final Properties j2meProps = new Properties();

	final HostBridge bridge = new HostBridge(this);

	private final List<ClassSource> sources = new ArrayList<>();
	/** guest class name -> substitute host class (adapter-installed; e.g. a
	 *  Timer subclass whose internals are reachable without reflection). */
	final Map<String, Class<?>> hostAliases = new HashMap<>();
	final List<VmThread> threads = new ArrayList<>();
	int rrCursor; // round-robin scan start; a busy thread must not starve later ones
	final Map<Class<?>, ShadowFactory> shadowFactories = new HashMap<>();
	final Map<Class<?>, HostHandle> hostHandles = new HashMap<>();
	final Map<Class<?>, ShadowCodec> shadowCodecs = new HashMap<>();
	private final Object lock = new Object();
	private final Object execLock = new Object(); // serializes all guest execution
	/**
	 * Bumped on every {@link #restore}: VmObjects stamp it at construction, so
	 * host code holding a pre-restore reference (a detached Canvas shadow still
	 * draining queued paint/hideNotify events) can't run guest code that writes
	 * into the live graph — statics are shared across epochs via VmClass.
	 */
	int epoch;

	/**
	 * Lazily assign/fetch the cross-restore serial for {@code o} and index it
	 * in {@link #liveBySerial}. Only proxy-bound objects pay for an entry.
	 * Skips serials already claimed by revived objects.
	 */
	synchronized long serialFor(VmObject o) {
		long s = o.serial;
		if (s == 0) {
			do {
				s = nextSerial++;
			} while (liveBySerial.containsKey(s));
			liveBySerial.put(s, o);
			o.serial = s;
		}
		return s;
	}
	/** guest-visible thread key (host Thread or VmObject for guest-extends-Thread) -> green thread */
	final IdentityHashMap<Object, VmThread> greenThreads = new IdentityHashMap<>();
	// package-visible for VmSampler's guest-frame attribution
	VmThread executor;
	Thread executorThread;

	static final class Monitor {
		VmThread owner;
		int count;
		final List<VmThread> waiters = new ArrayList<>();
		// threads BLOCKED trying to enter this monitor — replaces the old
		// O(threads) wake scan on every monExit (hot: CLDC Vector ops are
		// synchronized, games call them thousands of times per frame)
		final List<VmThread> contenders = new ArrayList<>();
	}

	public Vm() {
		j2meProps.setProperty("microedition.platform", "JL-Modern");
		j2meProps.setProperty("microedition.encoding", "UTF-8");
		// bundled guest-side CLDC classes — container/StringBuffer calls become
		// plain guest bytecode (fast, and snapshot-serializable for free)
		addClassSource(new ClassSource() {
			public byte[] findClass(String name) {
				InputStream in = Vm.class.getResourceAsStream("/cldc/" + name + ".classdata");
				if (in == null)
					return null;
				try {
					return readAll(in);
				} catch (IOException e) {
					return null;
				}
			}

			public InputStream findResource(String path) {
				return null;
			}
		});
		registerCldcFastCalls();
		registerLangFastCalls();
		registerDefaultHandles();
	}

	/**
	 * Fast paths for trivial host-side java.lang calls that games hammer every
	 * frame (Object.toString on host objects, Integer.parseInt of animation
	 * table strings, String.equals...). Each skips the reflective pipeline —
	 * the call0 guard already excludes VmObject receivers on virtual sites, so
	 * guest receivers keep their guest dispatch and only real host objects
	 * reach these lambdas. Kind 2 (invokespecial) shares the map — acceptable
	 * since javac never emits invokespecial for these leaf methods.
	 */
	private void registerLangFastCalls() {
		// Object.* on host receivers only (VmObject recv skips fastCall lookup)
		registerFastCall("java/lang/Object.toString()Ljava/lang/String;",
				a -> a[0].toString());
		registerFastCall("java/lang/Object.hashCode()I", a -> a[0].hashCode());
		registerFastCall("java/lang/Object.equals(Ljava/lang/Object;)Z",
				a -> a[0].equals(unshadow(a[1])));
		// String leaf calls
		registerFastCall("java/lang/String.toString()Ljava/lang/String;", a -> a[0]);
		registerFastCall("java/lang/String.length()I",
				a -> ((String) a[0]).length());
		registerFastCall("java/lang/String.charAt(I)C",
				a -> (int) ((String) a[0]).charAt((Integer) a[1]));
		registerFastCall("java/lang/String.equals(Ljava/lang/Object;)Z",
				a -> a[0].equals(unshadow(a[1])));
		registerFastCall("java/lang/String.substring(I)Ljava/lang/String;",
				a -> ((String) a[0]).substring((Integer) a[1]));
		registerFastCall("java/lang/String.substring(II)Ljava/lang/String;",
				a -> ((String) a[0]).substring((Integer) a[1], (Integer) a[2]));
		registerFastCall("java/lang/String.indexOf(I)I",
				a -> ((String) a[0]).indexOf((Integer) a[1]));
		registerFastCall("java/lang/String.trim()Ljava/lang/String;",
				a -> ((String) a[0]).trim());
		registerFastCall("java/lang/String.startsWith(Ljava/lang/String;)Z",
				a -> ((String) a[0]).startsWith((String) a[1]));
		registerFastCall("java/lang/String.valueOf(I)Ljava/lang/String;",
				a -> String.valueOf((Integer) a[0]));
		registerFastCall("java/lang/String.valueOf(J)Ljava/lang/String;",
				a -> String.valueOf((Long) a[0]));
		registerFastCall("java/lang/String.valueOf(Z)Ljava/lang/String;",
				a -> String.valueOf((Integer) a[0] != 0));
		// Integer
		registerFastCall("java/lang/Integer.parseInt(Ljava/lang/String;)I",
				a -> Integer.parseInt((String) a[0]));
		registerFastCall("java/lang/Integer.parseInt(Ljava/lang/String;I)I",
				a -> Integer.parseInt((String) a[0], (Integer) a[1]));
		registerFastCall("java/lang/Integer.toString()Ljava/lang/String;",
				a -> a[0].toString());
		registerFastCall("java/lang/Integer.toString(I)Ljava/lang/String;",
				a -> Integer.toString((Integer) a[0]));
		registerFastCall("java/lang/Integer.toString(II)Ljava/lang/String;",
				a -> Integer.toString((Integer) a[0], (Integer) a[1]));
		registerFastCall("java/lang/Integer.intValue()I", a -> a[0]);
		// Long
		registerFastCall("java/lang/Long.parseLong(Ljava/lang/String;)J",
				a -> Long.parseLong((String) a[0]));
		registerFastCall("java/lang/Long.toString()Ljava/lang/String;",
				a -> a[0].toString());
		registerFastCall("java/lang/Long.longValue()J", a -> a[0]);
		// Math leaf calls
		registerFastCall("java/lang/Math.abs(I)I", a -> Math.abs((Integer) a[0]));
		registerFastCall("java/lang/Math.abs(J)J", a -> Math.abs((Long) a[0]));
		registerFastCall("java/lang/Math.min(II)I",
				a -> Math.min((Integer) a[0], (Integer) a[1]));
		registerFastCall("java/lang/Math.max(II)I",
				a -> Math.max((Integer) a[0], (Integer) a[1]));
	}

	/** unwrap a guest object's host shadow for host-side equality checks. */
	private static Object unshadow(Object v) {
		if (v instanceof VmObject && ((VmObject) v).hostShadow != null)
			return ((VmObject) v).hostShadow;
		return v;
	}

	/**
	 * Built-in snapshot codecs for pure-JCL host objects that guest code
	 * legitimately holds onto — Date keeps its epoch ms, a resource
	 * ByteArrayInputStream keeps its remaining bytes (position resets, which
	 * is observably identical for read-once streams).
	 */
	private void registerDefaultHandles() {
		registerHostHandle(java.io.ByteArrayInputStream.class, new HostHandle() {
			public Object capture(Vm vm, Object h) {
				java.io.ByteArrayInputStream in = (java.io.ByteArrayInputStream) h;
				byte[] b = new byte[in.available()];
				in.mark(0);
				in.read(b, 0, b.length);
				in.reset();
				return b;
			}

			public Object revive(Vm vm, Object rec) {
				return new ByteArrayInputStream((byte[]) rec);
			}
		});
		registerHostHandle(java.util.Date.class, new HostHandle() {
			public Object capture(Vm vm, Object h) {
				return ((java.util.Date) h).getTime();
			}

			public Object revive(Vm vm, Object rec) {
				return new java.util.Date((Long) rec);
			}
		});
		// Games park resource streams in statics wrapped as DataInputStream
		// (Rainbow Six Vegas: getResourceAsStream -> static DataInputStream).
		// The wrapper adds no buffering of its own — its whole observable
		// state is "the remaining bytes", captured through the same
		// mark/read/reset trick as the bare-stream codec. No JDK internals
		// (setAccessible on FilterInputStream.in is module-blocked on JDK9+).
		registerHostHandle(java.io.DataInputStream.class, new HostHandle() {
			public Object capture(Vm vm, Object h) {
				java.io.DataInputStream dis = (java.io.DataInputStream) h;
				if (!dis.markSupported()) {
					// draining it would mutate live state — refuse loudly
					throw new VmError("DataInputStream over a non-markable"
							+ " stream — cannot snapshot");
				}
				try {
					dis.mark(Integer.MAX_VALUE);
					java.io.ByteArrayOutputStream bos =
							new java.io.ByteArrayOutputStream();
					byte[] buf = new byte[8192];
					for (int n; (n = dis.read(buf)) > 0; ) {
						bos.write(buf, 0, n);
					}
					dis.reset();
					return bos.toByteArray();
				} catch (java.io.IOException e) {
					throw new VmError("DataInputStream capture failed", e);
				}
			}

			public Object revive(Vm vm, Object rec) {
				return new java.io.DataInputStream(
						new ByteArrayInputStream((byte[]) rec));
			}
		});
		// Games park ByteArrayOutputStream buffers in statics (3D足球 resource
		// staging). Whole state is the buffered bytes; toByteArray() copies
		// them out without touching JDK internals.
		registerHostHandle(java.io.ByteArrayOutputStream.class, new HostHandle() {
			public Object capture(Vm vm, Object h) {
				return ((java.io.ByteArrayOutputStream) h).toByteArray();
			}

			public Object revive(Vm vm, Object rec) {
				java.io.ByteArrayOutputStream bos =
						new java.io.ByteArrayOutputStream();
				byte[] b = (byte[]) rec;
				bos.write(b, 0, b.length);
				return bos;
			}
		});
	}

	/**
	 * Fast paths for the bundled guest CLDC Vector's leaf methods. They read
	 * and write the receiver's guest fields directly — no interpreted frame,
	 * no reflection, and all state stays in the VmObject (snapshot-safe).
	 * Overridden methods in guest subclasses resolve to the subclass decl and
	 * never reach these lambdas.
	 */
	private void registerCldcFastCalls() {
		registerFastCall("java/util/Vector.size()I",
				a -> ((VmObject) a[0]).fields[vslot((VmObject) a[0], "elementCount|I")]);
		registerFastCall("java/util/Vector.isEmpty()Z",
				a -> (Integer) ((VmObject) a[0]).fields[vslot((VmObject) a[0],
						"elementCount|I")] == 0);
		registerFastCall("java/util/Vector.capacity()I",
				a -> ((Object[]) ((VmObject) a[0]).fields[vslot((VmObject) a[0],
						"elementData|[Ljava/lang/Object;")]).length);
		registerFastCall("java/util/Vector.elementAt(I)Ljava/lang/Object;", a -> {
			VmObject v = (VmObject) a[0];
			int i = (Integer) a[1];
			if (i >= (Integer) v.fields[vslot(v, "elementCount|I")])
				throw new ArrayIndexOutOfBoundsException(i);
			return ((Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")])[i];
		});
		registerFastCall("java/util/Vector.get(I)Ljava/lang/Object;", a -> {
			VmObject v = (VmObject) a[0];
			int i = (Integer) a[1];
			if (i >= (Integer) v.fields[vslot(v, "elementCount|I")])
				throw new ArrayIndexOutOfBoundsException(i);
			return ((Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")])[i];
		});
		registerFastCall("java/util/Vector.firstElement()Ljava/lang/Object;", a -> {
			VmObject v = (VmObject) a[0];
			if ((Integer) v.fields[vslot(v, "elementCount|I")] == 0)
				throw new java.util.NoSuchElementException();
			return ((Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")])[0];
		});
		registerFastCall("java/util/Vector.lastElement()Ljava/lang/Object;", a -> {
			VmObject v = (VmObject) a[0];
			int n = (Integer) v.fields[vslot(v, "elementCount|I")];
			if (n == 0)
				throw new java.util.NoSuchElementException();
			return ((Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")])[n - 1];
		});
		registerFastCall("java/util/Vector.setElementAt(Ljava/lang/Object;I)V", a -> {
			VmObject v = (VmObject) a[0];
			int i = (Integer) a[2];
			if (i >= (Integer) v.fields[vslot(v, "elementCount|I")])
				throw new ArrayIndexOutOfBoundsException(i);
			((Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")])[i] = a[1];
			return null;
		});
		registerFastCall("java/util/Vector.setSize(I)V", a -> {
			VmObject v = (VmObject) a[0];
			int ns = (Integer) a[1];
			int ci = vslot(v, "elementCount|I");
			int n = (Integer) v.fields[ci];
			if (ns > n) {
				vgrow(v, ns);
			} else {
				Object[] d = (Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")];
				for (int i = ns; i < n; i++)
					d[i] = null;
			}
			v.fields[ci] = ns;
			return null;
		});
		registerFastCall("java/util/Vector.ensureCapacity(I)V", a -> {
			vgrow((VmObject) a[0], (Integer) a[1]);
			return null;
		});
		registerFastCall("java/util/Vector.trimToSize()V", a -> {
			VmObject v = (VmObject) a[0];
			int di = vslot(v, "elementData|[Ljava/lang/Object;");
			Object[] d = (Object[]) v.fields[di];
			int n = (Integer) v.fields[vslot(v, "elementCount|I")];
			if (d.length > n) {
				Object[] nd = new Object[n];
				System.arraycopy(d, 0, nd, 0, n);
				v.fields[di] = nd;
			}
			return null;
		});
		registerFastCall("java/util/Vector.addElement(Ljava/lang/Object;)V", a -> {
			VmObject v = (VmObject) a[0];
			int ci = vslot(v, "elementCount|I");
			int n = (Integer) v.fields[ci];
			vgrow(v, n + 1);
			((Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")])[n] = a[1];
			v.fields[ci] = n + 1;
			return null;
		});
		registerFastCall("java/util/Vector.add(Ljava/lang/Object;)Z", a -> {
			VmObject v = (VmObject) a[0];
			int ci = vslot(v, "elementCount|I");
			int n = (Integer) v.fields[ci];
			vgrow(v, n + 1);
			((Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")])[n] = a[1];
			v.fields[ci] = n + 1;
			return true;
		});
		registerFastCall("java/util/Vector.insertElementAt(Ljava/lang/Object;I)V", a -> {
			VmObject v = (VmObject) a[0];
			int i = (Integer) a[2];
			int ci = vslot(v, "elementCount|I");
			int n = (Integer) v.fields[ci];
			if (i > n)
				throw new ArrayIndexOutOfBoundsException(i);
			vgrow(v, n + 1);
			Object[] d = (Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")];
			System.arraycopy(d, i, d, i + 1, n - i);
			d[i] = a[1];
			v.fields[ci] = n + 1;
			return null;
		});
		registerFastCall("java/util/Vector.removeElementAt(I)V", a -> {
			VmObject v = (VmObject) a[0];
			int i = (Integer) a[1];
			int ci = vslot(v, "elementCount|I");
			int n = (Integer) v.fields[ci];
			if (i >= n)
				throw new ArrayIndexOutOfBoundsException(i);
			Object[] d = (Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")];
			int j = n - i - 1;
			if (j > 0)
				System.arraycopy(d, i + 1, d, i, j);
			d[n - 1] = null;
			v.fields[ci] = n - 1;
			return null;
		});
		registerFastCall("java/util/Vector.removeAllElements()V", a -> {
			VmObject v = (VmObject) a[0];
			Object[] d = (Object[]) v.fields[vslot(v, "elementData|[Ljava/lang/Object;")];
			int n = (Integer) v.fields[vslot(v, "elementCount|I")];
			for (int i = 0; i < n; i++)
				d[i] = null;
			v.fields[vslot(v, "elementCount|I")] = 0;
			return null;
		});
		registerFastCall("java/util/Vector.copyInto([Ljava/lang/Object;)V", a -> {
			VmObject v = (VmObject) a[0];
			System.arraycopy(v.fields[vslot(v, "elementData|[Ljava/lang/Object;")], 0,
					a[1], 0, (Integer) v.fields[vslot(v, "elementCount|I")]);
			return null;
		});
		registerFastCall("java/util/Vector.toArray()[Ljava/lang/Object;", a -> {
			VmObject v = (VmObject) a[0];
			int n = (Integer) v.fields[vslot(v, "elementCount|I")];
			Object[] r = new Object[n];
			System.arraycopy(v.fields[vslot(v, "elementData|[Ljava/lang/Object;")], 0,
					r, 0, n);
			return r;
		});
	}

	private static int vslot(VmObject v, String key) {
		Integer i = v.clazz.fieldSlots.get(key);
		if (i == null)
			throw new VmError("Vector layout missing " + key);
		return i;
	}

	/** Mirrors the guest Vector.grow() capacity policy. */
	private static void vgrow(VmObject v, int minCapacity) {
		int di = vslot(v, "elementData|[Ljava/lang/Object;");
		Object[] d = (Object[]) v.fields[di];
		if (minCapacity <= d.length)
			return;
		int inc = (Integer) v.fields[vslot(v, "capacityIncrement|I")];
		int cap = inc > 0 ? d.length + inc : d.length * 2;
		if (cap < minCapacity)
			cap = minCapacity;
		Object[] nd = new Object[cap];
		System.arraycopy(d, 0, nd, 0, (Integer) v.fields[vslot(v, "elementCount|I")]);
		v.fields[di] = nd;
	}

	/** register a classpath. dirMode: findClass gets "com/foo/Bar" -> bytes. */
	public void addClassSource(ClassSource src) {
		sources.add(src);
	}

	/**
	 * Substitute {@code cls} for the host class a guest name resolves to.
	 * Must be installed before the class is first resolved (resolution is
	 * cached). Adapter use only — e.g. a Timer subclass whose internals are
	 * reachable without reflection.
	 */
	public void addHostAlias(String name, Class<?> cls) {
		hostAliases.put(name, cls);
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
			Class<?> aliased = hostAliases.get(name);
			Class<?> hc = aliased != null
					? aliased : Class.forName(name.replace('/', '.'));
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
			if (in != null) {
				// J2ME jar resources must deliver all bytes in one read():
				// Android's ZipFile inflater returns short reads on DEFLATED
				// entries (measured 468/471), which corrupts games that read
				// a resource into a fixed buffer with a single call.
				try {
					byte[] all = readAll(in);
					in.close();
					return new ByteArrayInputStream(all);
				} catch (IOException x) {
					return null;
				}
			}
		}
		return null;
	}

	// ---------- scheduling ----------

	VmThread newThread() {
		VmThread t = new VmThread(this);
		synchronized (threads) {
			threads.add(t);
			if (++threadCreates >= 1024) {
				threadCreates = 0;
				cullDeadThreads();
			}
		}
		return t;
	}

	/**
	 * Drop TERMINATED threads nothing can observe — host-entry corpses pile up
	 * at ~1 per host event otherwise, and every one costs a record in each
	 * snapshot. Kept: live threads, corpses still bound to a Thread box
	 * (greenThreads values — guest isAlive()/join() needs the object), and
	 * corpses referenced by kept threads' join links (fixpoint).
	 */
	void cullDeadThreads() {
		IdentityHashMap<VmThread, Boolean> keep = new IdentityHashMap<>();
		synchronized (greenThreads) {
			for (VmThread t : greenThreads.values())
				keep.put(t, Boolean.TRUE);
		}
		synchronized (threads) {
			for (VmThread t : threads)
				if (t.isAlive())
					keep.put(t, Boolean.TRUE);
			boolean changed = true;
			while (changed) {
				changed = false;
				for (VmThread t : threads) {
					if (!keep.containsKey(t))
						continue;
					if (t.joinTarget != null
							&& keep.put(t.joinTarget, Boolean.TRUE) == null) {
						changed = true;
					}
					synchronized (t.joinWaiters) {
						for (VmThread w : t.joinWaiters)
							if (keep.put(w, Boolean.TRUE) == null)
								changed = true;
					}
				}
			}
			threads.removeIf(t -> !t.isAlive() && !keep.containsKey(t));
		}
	}

	void wake(VmThread t) {
		t.state = VmThread.RUNNABLE;
	}

	/** Debug dump: state + top frames of every live green thread. */
	public String dumpThreads() {
		return dumpThreads(false);
	}

	/**
	 * Guest stack of the thread currently executing a host call, or null when
	 * not on the executor path — for logging from inside host methods.
	 */
	public String guestStackTop() {
		if (Thread.currentThread() != executorThread || executor == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("[gt").append(Integer.toHexString(
				System.identityHashCode(executor))).append("] ");
		for (int i = executor.frames.size() - 1; i >= 0; i--) {
			Frame f = executor.frames.get(i);
			sb.append(f.owner.name).append('.').append(f.method.name)
					.append('@').append(f.pc);
			if (i > 0) {
				sb.append(" <- ");
			}
		}
		return sb.toString();
	}

	/** Identity of the green thread currently inside a host call, or 0. */
	public int guestThreadId() {
		if (Thread.currentThread() != executorThread || executor == null) {
			return 0;
		}
		return System.identityHashCode(executor);
	}

	/** deep=true also prints a locals[0] field summary per frame (guest objects only). */
	public String dumpThreads(boolean deep) {
		StringBuilder sb = new StringBuilder();
		// frames mutate under execLock, the list under threads — take execLock
		// outermost to match the global execLock→threads order.
		synchronized (execLock) {
			synchronized (threads) {
				for (VmThread t : threads) {
					if (!t.isAlive() && !deep)
						continue;
					sb.append("t#").append(Integer.toHexString(System.identityHashCode(t)))
							.append(" state=").append(t.state)
							.append(t.waitMonitor != null ? " waitMon" : "")
							.append(" frames=").append(t.frames.size()).append('\n');
					for (int i = t.frames.size() - 1; i >= 0 && i > t.frames.size() - 4; i--) {
						Frame f = t.frames.get(i);
						sb.append("  at ").append(f.owner.name).append('.')
								.append(f.method.name).append(f.method.desc)
								.append(" @").append(f.pc).append('\n');
						if (deep && f.locals.length > 0 && f.locals[0] instanceof VmObject)
							appendFields(sb, (VmObject) f.locals[0]);
					}
				}
			}
		}
		return sb.toString();
	}

	private static void appendFields(StringBuilder sb, VmObject o) {
		sb.append("    this=").append(o.clazz.name);
		for (Map.Entry<String, Integer> e : o.clazz.fieldSlots.entrySet()) {
			Object v = o.fields[e.getValue()];
			if (v == null)
				continue;
			String k = e.getKey();
			sb.append(' ').append(k, 0, k.indexOf('|')).append('=').append(shortVal(v));
		}
		sb.append('\n');
	}

	private static String shortVal(Object v) {
		if (v instanceof VmObject) {
			VmObject o = (VmObject) v;
			if (o.clazz.name.equals("java/util/Vector")) {
				Integer slot = o.clazz.fieldSlots.get("elementCount|I");
				Object n = slot == null ? "?" : o.fields[slot];
				return "Vector#" + Integer.toHexString(System.identityHashCode(o))
						+ "(size=" + n + ")";
			}
			return o.clazz.name + "#" + Integer.toHexString(System.identityHashCode(o));
		}
		if (v instanceof Object[])
			return "Obj[" + ((Object[]) v).length + "]";
		if (v instanceof byte[])
			return "byte[" + ((byte[]) v).length + "]";
		if (v instanceof int[])
			return "int[" + ((int[]) v).length + "]";
		String s = String.valueOf(v);
		return s.length() > 24 ? s.substring(0, 24) : s;
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

	/** cumulative guest instructions executed; wraps naturally. */
	public volatile long totalOps;

	/** diagnostics: runUntilDone exits where the entry thread was still parked. */
	public volatile int abandonedRuns;

	/** diagnostics: guest sleep() calls and requested ms. */
	public volatile int sleepCalls;
	public volatile long sleepMsTotal;

	/** diagnostics: per-callsite host-call counts (shared live map). */
	public java.util.Map<String, java.util.concurrent.atomic.AtomicLong> callStats() {
		return bridge.callStats;
	}

	/** Per-call stat counting costs a concat + map ops — off unless diagnostics needed. */
	public void setCallStatsEnabled(boolean on) {
		bridge.statsOn = on;
	}

	/** diagnostics: sampling profiler — when on, each pump slice attributes a
	 *  time-weighted sample to the method on top of the slice's thread stack. */
	public volatile boolean profileOn;
	public final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> profStats =
			new java.util.HashMap<>();

	private void profSample(VmThread t) {
		Frame f = t.top();
		if (f == null)
			return;
		String k = f.owner.name + "." + f.method.name + f.method.desc;
		synchronized (profStats) {
			java.util.concurrent.atomic.AtomicLong c = profStats.get(k);
			if (c == null)
				profStats.put(k, c = new java.util.concurrent.atomic.AtomicLong());
			c.incrementAndGet();
		}
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
				if (profileOn)
					profSample(t);
				if (budget > 0 && used >= budget)
					break;
			}
			totalOps += used;
		}
	}

	private VmThread nextRunnable() {
		long now = System.currentTimeMillis();
		synchronized (threads) {
			int n = threads.size();
			for (int k = 0; k < n; k++) {
				int idx = (rrCursor + k) % n;
				VmThread t = threads.get(idx);
				if (t.state == VmThread.RUNNABLE && !t.frames.isEmpty()
						|| t.state == VmThread.TIMED_WAITING && now >= t.wakeTime) {
					// resume scanning after this thread so every runnable thread gets slices
					rrCursor = (idx + 1) % n;
					return t; // TIMED_WAITING: Interpreter will retry its op (state restored inside)
				}
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
			m.contenders.add(t);
			return false;
		}
	}

	/** Wake one thread BLOCKED entering m; prunes stale entries while here.
	 *  Caller must hold synchronized(m). */
	private static void wakeContender(Monitor m) {
		boolean woke = false;
		for (int i = 0; i < m.contenders.size(); i++) {
			VmThread w = m.contenders.get(i);
			if (w.state != VmThread.BLOCKED || w.waitMonitor != m) {
				m.contenders.remove(i--); // reacquired / terminated / notified-away
				continue;
			}
			if (!woke) {
				w.state = VmThread.RUNNABLE;
				woke = true;
			}
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
				wakeContender(m);
			}
		}
	}

	void objWait(VmThread t, Object obj, long ms) {
		Monitor m = monitorOf(obj);
		synchronized (m) {
			if (m.owner != t)
				throw new VmException(new IllegalMonitorStateException("wait without monitor"));
			if (t.interrupted) {
				// interrupted before parking: flag clears, monitor stays held
				t.interrupted = false;
				throw new VmException(new InterruptedException());
			}
			int released = m.count;
			m.owner = null;
			m.count = 0;
			m.waiters.add(t);
			recordInterruptPc(t);
			t.state = ms > 0 ? VmThread.TIMED_WAITING : VmThread.WAITING;
			t.wakeTime = ms > 0 ? System.currentTimeMillis() + ms : 0;
			t.waitMonitor = m;
			t.waitReleasedCount = released;
			t.resumingFromWait = true;
			wakeContender(m);
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
				m.contenders.add(w);
			}
		}
	}

	// ---------- entry points ----------

	public Object invokeStatic(String className, String method, String desc, Object... args) {
		VmClass c = resolveClass(className);
		VmThread t = newThread();
		t.hostThread = Thread.currentThread();
		t.hostEntry = true;
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
		t.hostEntry = true;
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
		t.hostEntry = true;
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
	/**
	 * True when the current thread holds execLock, i.e. is inside a slice or
	 * nested host call. A parked-call handler may use this to run another
	 * guest entry inline on the same host thread without waiting for an
	 * external executor — execLock is reentrant and nested entry threads are
	 * independent VmThreads.
	 */
	public boolean inExecLock() {
		return Thread.holdsLock(execLock);
	}

	private void runUntilDone(VmThread t) {
		for (;;) {
			synchronized (execLock) {
				if (t.restoreOrphan) {
					// a restore replaced this thread: the revived copy carries
					// the continuation — abandon the stale-graph call
					t.terminate();
					return;
				}
				if (canRun(t))
					totalOps += runSlice(t, Long.MAX_VALUE);
				if (!t.isAlive())
					return;
				if (canRun(t))
					continue;
				// t parked: let other green threads try to unblock it
				pump();
				if (!t.isAlive() || canRun(t))
					continue;
				if (pendingWork() < 0 && t.waitMonitor != PARKED) {
					if (guestDeadlocked())
						throw new VmError("guest deadlock: no runnable, timed or host-parked thread\n"
								+ dumpThreads());
					abandonedRuns++; // diagnostics: host callback returned with guest frames still parked
					return;
				}
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

	/** True when no live thread can ever run again: nothing runnable or timed,
	 *  and nothing parked on a host callback (PARKED — the host may still
	 *  complete it and produce guest-side wakeups). */
	private boolean guestDeadlocked() {
		synchronized (threads) {
			boolean any = false;
			for (VmThread u : threads) {
				if (!u.isAlive() || u.frames.isEmpty())
					continue;
				any = true;
				if (u.state == VmThread.RUNNABLE || u.state == VmThread.TIMED_WAITING
						|| u.waitMonitor == PARKED)
					return false;
			}
			return any;
		}
	}

	void pushStatic(VmThread t, VmClass c, String name, String desc, Object[] args) {
		ensureInitBlocking(c, t);
		ClassFile.Method m = c.findDeclared(name, desc);
		if (m == null || (m.access & 0x0008) == 0)
			throw new VmError("no static " + c.name + "." + name + desc);
		Frame f = allocFrame(c, m);
		f.external = true;
		fillArgs(f, desc, args, false);
		pushFrame(t, f);
	}

	/**
	 * Push a frame on an entry-point thread. The thread may have been marked
	 * TERMINATED by a nested run (e.g. a blocking clinit); revive it so the
	 * scheduler picks it up again.
	 */
	/** Take a pooled frame of exactly this method's array sizes, or allocate. */
	Frame allocFrame(VmClass owner, ClassFile.Method m) {
		int key = (m.code.maxLocals << 16) | (m.code.maxStack + 1);
		java.util.ArrayDeque<Frame> q = framePools.get(key);
		Frame f = q == null ? null : q.poll();
		if (f != null) {
			f.reset(owner, m);
			return f;
		}
		return new Frame(owner, m);
	}

	/** Return a dead frame to its size bucket (bounded — pools only need hot sizes). */
	void freeFrame(Frame f) {
		int key = (f.locals.length << 16) | f.stack.length;
		java.util.ArrayDeque<Frame> q = framePools.get(key);
		if (q == null)
			framePools.put(key, q = new java.util.ArrayDeque<Frame>());
		if (q.size() < 64)
			q.add(f);
	}

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
				if (Interpreter.TRACE_CALLS != null)
					System.err.println("pushInvoke host-fallback " + recv
							+ "." + name + desc + " owner=" + owner.name);
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
			Frame f = allocFrame(ownerOf(owner, m), m);
			f.external = true;
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
			f.locals[li++] = c == 'L' ? fromHost(v, false) : fromHost(v);
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
		if (recv instanceof VmObject && ((VmObject) recv).epoch != epoch) {
			// receiver belongs to a discarded pre-restore graph — dropping the
			// call; anything it touched would corrupt the live graph's statics.
			// Cap the log: a queued TimerTask can fire this every tick forever.
			if (dropLogCount++ < 100)
				System.err.println("GuestDrop: " + name + desc + " on stale " + recv);
			return null;
		}
		VmThread cur;
		if (Thread.currentThread() == executorThread && (cur = executor) != null) {
			if (!(recv instanceof VmObject)) {
				VmClass hc = resolveClass(recv.getClass().getName());
				return bridge.call(cur, hc.name, name, desc, joinArgs(recv, args), 0);
			}
			int depth = cur.frames.size();
			cur.nestedError = null;
			pushInvoke(cur, recv, name, desc, joinArgs(recv, args));
			Interpreter.execute(this, cur, Long.MAX_VALUE, depth);
			if (cur.nestedError != null) {
				VmException ve = cur.nestedError;
				cur.nestedError = null;
				throw ve;
			}
			return cur.nestedResult;
		}
		VmThread t = newThread();
		t.hostThread = Thread.currentThread();
		t.hostEntry = true;
		synchronized (execLock) {
			pushInvoke(t, recv, name, desc, joinArgs(recv, args));
		}
		runUntilDone(t);
		if (t.lastError != null)
			throw t.lastError;
		return t.result;
	}

	/**
	 * Host callback addressed by serial: resolves the live object for
	 * {@code serial} and enqueues the invocation in one execLock section, so a
	 * restore cannot land between lookup and dispatch — a queued host event
	 * always reaches the epoch that owns the object it points at.
	 * @return the call result, or {@link #DROPPED} when the serial has no live
	 *         object (the guest was discarded pre-save or never snapshotted).
	 */
	public Object callForSerial(long serial, String name, String desc,
			Object... args) {
		VmThread cur;
		if (Thread.currentThread() == executorThread && (cur = executor) != null) {
			// nested inside a slice: execLock already held by this thread
			VmObject g;
			synchronized (this) {
				g = liveBySerial.get(serial);
			}
			if (g == null || g.isStale())
				return DROPPED;
			int depth = cur.frames.size();
			cur.nestedError = null;
			pushInvoke(cur, g, name, desc, joinArgs(g, args));
			Interpreter.execute(this, cur, Long.MAX_VALUE, depth);
			if (cur.nestedError != null) {
				VmException ve = cur.nestedError;
				cur.nestedError = null;
				throw ve;
			}
			return cur.nestedResult;
		}
		VmThread t = newThread();
		t.hostThread = Thread.currentThread();
		t.hostEntry = true;
		synchronized (execLock) {
			VmObject g;
			synchronized (this) {
				g = liveBySerial.get(serial);
			}
			if (g == null || g.isStale()) {
				t.terminate();
				return DROPPED;
			}
			pushInvoke(t, g, name, desc, joinArgs(g, args));
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
			Frame f = allocFrame(x, clinit);
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
		if (t.interrupted) {
			// interrupted before parking: flag clears, IE at the call site
			t.interrupted = false;
			throw new VmException(new InterruptedException());
		}
		sleepCalls++;
		sleepMsTotal += ms;
		recordInterruptPc(t);
		t.state = VmThread.TIMED_WAITING;
		t.wakeTime = System.currentTimeMillis() + ms;
	}

	/** Thread.interrupt() on a green thread: set the flag; if the thread is in
	 *  an interruptible park (sleep/wait/join), wake it and mark the exception
	 *  for delivery on resume. Monitor-contention BLOCKED, host-parked and
	 *  clinit-gated threads only get the flag — matching JVM semantics. */
	void interruptGreen(VmThread g) {
		if (g == null || !g.isAlive())
			return;
		g.interrupted = true;
		if (g.state != VmThread.WAITING && g.state != VmThread.TIMED_WAITING)
			return;
		Object wm = g.waitMonitor;
		if (wm == PARKED || wm instanceof VmClass)
			return; // host-callback park and the clinit gate are not interruptible
		if (wm instanceof Monitor) {
			Monitor m = (Monitor) wm;
			synchronized (m) {
				m.waiters.remove(g); // leave the wait set; reacquire() still applies
			}
		}
		if (g.joinTarget != null) {
			g.joinTarget.removeJoinWaiter(g);
			g.joinTarget = null;
		}
		g.pendingInterrupt = true;
		g.state = VmThread.RUNNABLE;
	}

	private static void recordInterruptPc(VmThread t) {
		Frame f = t.top();
		t.interruptPc = f != null ? f.pc : -1;
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
		return fromHost(v, true);
	}

	/**
	 * host -> guest into a slot of known static type. Shadows always unwrap;
	 * int-family wrappers normalize to Integer only for primitive slots —
	 * reference-typed slots keep the real object so checkcast/instanceof on
	 * java/lang/Short & friends see real semantics.
	 */
	static Object fromHost(Object v, boolean primitiveSlot) {
		if (v instanceof GuestShadow)
			return ((GuestShadow) v).guest();
		if (!primitiveSlot)
			return v;
		if (v instanceof Boolean)
			return (Boolean) v ? 1 : 0;
		if (v instanceof Character)
			return (int) (Character) v;
		if (v instanceof Byte || v instanceof Short)
			return ((Number) v).intValue();
		return v;
	}

	void park(VmThread t, long ms) {
		if (t.interrupted) {
			t.interrupted = false;
			throw new VmException(new InterruptedException());
		}
		if (ms > 0) {
			greenSleep(t, ms);
		} else {
			recordInterruptPc(t);
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
		if (Interpreter.TRACE_CALLS != null)
			System.err.println("startGreen key=" + key + " target=" + target);
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

	final Map<String, ParkedCall> parkedCalls = new HashMap<>();
	private final Map<String, ParkedCall> parkedCache = new HashMap<>();
	private static final ParkedCall NONE = (v, t, a, d) -> { };
	static final Object PARKED = new Object();
	/** callForSerial result when the serial resolves to no live object. */
	static final Object DROPPED = new Object();

	/**
	 * A host-call fast path: invokes directly without reflection/marshal.
	 * args[0] is the receiver for instance methods; values arrive in guest
	 * representation (int-family as Integer, long as Long, refs raw).
	 */
	public interface FastCall {
		Object invoke(Object[] args) throws Exception;
	}

	private final Map<String, FastCall> fastCalls = new HashMap<>();

	/** key format: "javax/microedition/lcdui/Graphics.drawImage(Ljavax/microedition/lcdui/Image;III)V" */
	public void registerFastCall(String methodKey, FastCall call) {
		fastCalls.put(methodKey, call);
	}

	FastCall fastCall(String ownerName, String name, String desc) {
		return fastCalls.get(ownerName + "." + name + desc);
	}

	/** key format: "javax/microedition/lcdui/Canvas.serviceRepaints()V" */
	public void registerParkedCall(String methodKey, ParkedCall call) {
		parkedCalls.put(methodKey, call);
	}

	// ---------- snapshots ----------

	/**
	 * Codec for host objects that leak into guest state (Bitmaps, streams...).
	 * Lookup walks superclasses then interfaces. An unregistered host object
	 * reachable from guest state aborts {@link #checkpoint} with a VmError.
	 */
	public void registerHostHandle(Class<?> type, HostHandle h) {
		hostHandles.put(type, h);
	}

	/** State codec for shadows produced by a ShadowFactory, keyed by the same
	 *  host base class. A guest object carrying a hostShadow with no registered
	 *  codec aborts {@link #checkpoint} — restoring a fresh shadow while
	 *  dropping its state would corrupt silently. */
	public void registerShadowCodec(Class<?> hostBase, ShadowCodec c) {
		shadowCodecs.put(hostBase, c);
	}

	/**
	 * Freeze the whole guest machine to bytes under execLock — safe to call at
	 * any scheduling boundary (between pump slices, on a UI action). Threads
	 * mid host call (PARKED) are recorded for replay on restore.
	 */
	public byte[] checkpoint() {
		synchronized (execLock) {
			cullDeadThreads();
			return Snapshot.write(this);
		}
	}

	/**
	 * Rebuild all guest state from a snapshot on a Vm constructed with the
	 * same ClassSources/shadows/fastcalls/parkedCalls as the original. Works
	 * on a fresh or a live Vm — existing state is discarded.
	 */
	public void restore(byte[] snap) {
		synchronized (execLock) {
			epoch++;
			Snapshot.read(this, snap);
		}
	}

	ParkedCall parkedCall(String ownerName, String name, String desc) {
		String key = ownerName + "." + name + desc;
		ParkedCall pc = parkedCache.get(key);
		if (pc != null)
			return pc == NONE ? null : pc;
		String suffix = "." + name + desc;
		pc = parkedCalls.get(ownerName + suffix);
		if (pc == null) {
			// the call site names a guest class's host boundary (e.g. FullCanvas),
			// not the registering class — walk the host hierarchy
			try {
				for (Class<?> h = Class.forName(ownerName.replace('/', '.'));
						h != null && pc == null; h = h.getSuperclass()) {
					pc = parkedCalls.get(h.getName().replace('.', '/') + suffix);
					if (pc == null) {
						for (Class<?> i : h.getInterfaces()) {
							pc = parkedCalls.get(i.getName().replace('.', '/') + suffix);
							if (pc != null)
								break;
						}
					}
				}
			} catch (ClassNotFoundException ignored) {
			}
		}
		parkedCache.put(key, pc == null ? NONE : pc);
		return pc;
	}

	/** Run a parked call: the green thread sleeps until the handler's done. */
	Object callParked(VmThread t, ParkedCall pc, Object[] args) {
		t.state = VmThread.WAITING;
		t.waitMonitor = PARKED;
		// record the call so a snapshot restore can replay it — the done closure
		// itself is not serializable
		for (Map.Entry<String, ParkedCall> e : parkedCalls.entrySet()) {
			if (e.getValue() == pc) {
				t.parkedCallKey = e.getKey();
				break;
			}
		}
		t.parkedArgs = args;
		final boolean[] resumed = {false};
		Runnable done = () -> {
			synchronized (threads) {
				if (resumed[0])
					return;
				resumed[0] = true;
				t.parkedCallKey = null;
				t.parkedArgs = null;
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
					if (m.owner == null)
						wakeContender(m); // contenders re-contend or re-park
				}
		}
	}
}
