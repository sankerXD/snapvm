package org.snapvm;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A class visible to the VM. Either a guest class (parsed .class, interpreted)
 * or a host class (real java.lang.Class, dispatched reflectively).
 */
public final class VmClass {
	final Vm vm;
	final String name;              // binary-ish name with '/' separators, or "[I" style for arrays
	final boolean host;
	final Class<?> hostClass;       // != null iff host
	ClassFile cf;                   // guest only
	VmClass superClass;
	VmClass[] interfaces;
	int access;

	// guest layout
	int instanceSlotCount;
	Object[] statics;
	Map<String, Integer> fieldSlots;            // own instance fields: name+desc -> global slot idx
	Object[] fieldDefaults;                     // per-slot JVM default (0/false/null), copied per instance
	final Map<String, ClassFile.Method> declaredMethods = new HashMap<>();
	final Map<String, ClassFile.Method> methodMap = new HashMap<>(); // merged virtual dispatch
	Map<String, Integer> staticFieldSlots;      // own statics: name+desc -> idx into this.statics
	final Map<String, Object> staticDefaults = new HashMap<>();

	// init state
	boolean initDone;
	boolean initRunning;
	VmThread initThread;

	private GuestClass guestClassObj;

	VmClass(Vm vm, String name, ClassFile cf) {
		this.vm = vm;
		this.name = name;
		this.cf = cf;
		this.host = false;
		this.hostClass = null;
		this.access = cf != null ? cf.access : 0; // null cf = guest array type
	}

	VmClass(Vm vm, String name, Class<?> hostClass) {
		this.vm = vm;
		this.name = name;
		this.host = true;
		this.hostClass = hostClass;
		this.access = hostClass.getModifiers();
		this.initDone = true; // host classes need no guest-side init
	}

	public boolean isInterface() {
		return host ? hostClass.isInterface() : (access & 0x0200) != 0;
	}

	public boolean isArray() {
		return host ? hostClass.isArray() : name.startsWith("[");
	}

	public boolean isGuest() {
		return !host;
	}

	public String binaryName() {
		return name.replace('/', '.');
	}

	public GuestClass guestClass() {
		if (guestClassObj == null)
			guestClassObj = new GuestClass(this);
		return guestClassObj;
	}

	/** this <- other assignability (other may be guest, host, array, interface). */
	public boolean isAssignableFrom(VmClass other) {
		if (other == null)
			return false;
		if (this == other)
			return true;
		if (host && other.host)
			return hostClass.isAssignableFrom(other.hostClass);
		if (name.equals("java/lang/Object"))
			return true;
		if (other.isArray()
				&& (name.equals("java/lang/Cloneable") || name.equals("java/io/Serializable")))
			return true;
		if (isArray() && other.isArray()) {
			VmClass tc = arrayComponent(), oc = other.arrayComponent();
			if (tc == null || oc == null)
				return tc == oc; // primitive components must match exactly
			return tc.isAssignableFrom(oc);
		}
		if (!host && other.host) {
			// guest interface vs host object: only if interface is host-implemented? rare; be strict
			return false;
		}
		if (host) {
			// host target vs guest source: the guest's class chain may cross into a host
			// superclass or implement a host interface — walk and compare host classes
			return hostAssignable(other);
		}
		// both guest
		for (VmClass c = other; c != null; c = c.superClass) {
			if (c == this)
				return true;
			if (c.interfaces != null)
				for (VmClass i : c.interfaces)
					if (i != null && isAssignableFrom(i))
						return true;
		}
		return false;
	}

	/** this (host) <- other: true if other's chain reaches a host class assignable to this. */
	private boolean hostAssignable(VmClass other) {
		for (VmClass c = other; c != null; c = c.superClass) {
			if (c.host)
				return hostClass.isAssignableFrom(c.hostClass);
			if (c.interfaces != null)
				for (VmClass i : c.interfaces)
					if (i != null && hostAssignable(i))
						return true;
		}
		return false;
	}

	/** first host class on this class's extends chain, or null if purely guest-rooted. */
	VmClass hostBoundary() {
		VmClass c = this;
		while (c != null && !c.host)
			c = c.superClass;
		return c;
	}

	/** runtime instanceof for a concrete object reference. */
	public boolean isInstance(Object obj) {
		if (obj == null)
			return false;
		if (obj instanceof VmObject) {
			VmClass oc = ((VmObject) obj).clazz;
			return isAssignableFrom(oc);
		}
		if (obj.getClass().isArray()) {
			if (name.equals("java/lang/Object") || name.equals("java/lang/Cloneable")
					|| name.equals("java/io/Serializable"))
				return true;
			if (host)
				return hostClass.isInstance(obj);
			if (!isArray())
				return false;
			// guest-side array type ([Lguest/Foo; or deeper): compare components —
			// the object's component is tracked in guestArrayTypes or, for real
			// host arrays, its reflective component class
			VmClass tc = arrayComponent();
			VmClass oc = runtimeComponent(obj);
			if (tc == null || oc == null)
				return tc == oc;
			return tc.isAssignableFrom(oc);
		}
		if (obj instanceof GuestClass)
			return name.equals("java/lang/Class") || name.equals("java/lang/Object");
		// host object
		if (host)
			return hostClass.isInstance(obj);
		return name.equals("java/lang/Object");
	}

	/**
	 * The component type of an array class, or null for primitive components and
	 * non-array classes.
	 */
	VmClass arrayComponent() {
		if (!isArray())
			return null;
		if (host) {
			Class<?> c = hostClass.getComponentType();
			if (c == null || c.isPrimitive())
				return null;
			return vm.resolveClass(c.getName());
		}
		String s = name.substring(1);
		if (s.charAt(0) == 'L')
			s = s.substring(1, s.length() - 1);
		return vm.resolveClass(s);
	}

	/** The runtime component type of an array OBJECT: tracked for guest arrays, reflective for host ones. */
	private VmClass runtimeComponent(Object array) {
		VmClass tracked = vm.guestArrayTypes.get(array);
		if (tracked != null)
			return tracked;
		Class<?> comp = array.getClass().getComponentType();
		if (comp == null || comp.isPrimitive())
			return null;
		return vm.resolveClass(comp.getName());
	}

	void link() {
		if (cf == null)
			return;
		if (cf.superName != null)
			superClass = vm.resolveClass(cf.superName);
		else if (!name.equals("java/lang/Object"))
			superClass = vm.resolveClass("java/lang/Object");
		if (cf.interfaces != null) {
			interfaces = new VmClass[cf.interfaces.length];
			for (int i = 0; i < cf.interfaces.length; i++)
				interfaces[i] = vm.resolveClass(cf.interfaces[i]);
		}
		buildMemberTables();
	}

	private void buildMemberTables() {
		List<VmClass> chain = new ArrayList<>();
		for (VmClass c = this; c != null && !c.host; c = c.superClass)
			chain.add(0, c);
		fieldSlots = new HashMap<>();
		staticFieldSlots = new HashMap<>();
		int inst = 0, stat = 0;
		for (VmClass c : chain) {
			for (ClassFile.Field f : c.cf.fields) {
				String key = f.name + "|" + f.desc;
				if ((f.access & 0x0008) != 0) {
					if (c == this) {
						staticFieldSlots.put(key, stat++);
						if (f.constantValue != null)
							staticDefaults.put(key, f.constantValue);
					}
				} else {
					fieldSlots.put(key, inst++);
					if (isWide(f.desc))
						inst++;
				}
			}
		}
		for (ClassFile.Method m : cf.methods) {
			declaredMethods.put(m.name + "|" + m.desc, m);
		}
		for (VmClass c : chain) {
			for (ClassFile.Method m : c.cf.methods)
				methodMap.put(m.name + "|" + m.desc, m);
		}
		instanceSlotCount = inst;
		fieldDefaults = new Object[inst];
		for (Map.Entry<String, Integer> e : fieldSlots.entrySet())
			fieldDefaults[e.getValue()] = defaultValue(e.getKey());
		statics = new Object[stat];
		for (Map.Entry<String, Integer> e : staticFieldSlots.entrySet())
			statics[e.getValue()] = defaultValue(e.getKey());
	}

	/** JVM default value for a field type; desc arrives as "name|desc". */
	private static Object defaultValue(String nameAndDesc) {
		switch (nameAndDesc.charAt(nameAndDesc.lastIndexOf('|') + 1)) {
		case 'Z':
		case 'B':
		case 'S':
		case 'C':
		case 'I':
			return 0;
		case 'J':
			return 0L;
		case 'F':
			return 0f;
		case 'D':
			return 0d;
		default:
			return null;
		}
	}

	static boolean isWide(String desc) {
		return desc.equals("J") || desc.equals("D");
	}

	/** static/special resolution: find method in this class or guest supers. */
	ClassFile.Method findDeclared(String name, String desc) {
		return findDeclared(name + "|" + desc);
	}

	/** key = name|desc, precomputed on the Ref — no per-call concat. */
	ClassFile.Method findDeclared(String key) {
		for (VmClass c = this; c != null && !c.host; c = c.superClass) {
			ClassFile.Method m = c.declaredMethods.get(key);
			if (m != null)
				return m;
		}
		// interface superinterfaces for invokeinterface
		if (interfaces != null)
			for (VmClass i : interfaces) {
				ClassFile.Method m = i.findDeclared(key);
				if (m != null)
					return m;
			}
		return null;
	}

	/** resolve instance/virtual method: search this class's merged table (covers overrides). */
	ClassFile.Method findMethod(String name, String desc) {
		return methodMap.get(name + "|" + desc);
	}

	ClassFile.Method findMethod(String key) {
		return methodMap.get(key);
	}

	/** instance field slot index (global, flattened over supers); null if absent. */
	Integer fieldSlot(String name, String desc) {
		for (VmClass c = this; c != null && !c.host; c = c.superClass) {
			Integer s = c.fieldSlots.get(name + "|" + desc);
			if (s != null)
				return s;
		}
		return null;
	}

	/** class in the hierarchy that declares this static field; null if absent. */
	VmClass staticFieldOwner(String name, String desc) {
		String key = name + "|" + desc;
		for (VmClass c = this; c != null && !c.host; c = c.superClass) {
			if (c.staticFieldSlots.containsKey(key))
				return c;
		}
		if (interfaces != null)
			for (VmClass i : interfaces) {
				VmClass o = i.staticFieldOwner(name, desc);
				if (o != null)
					return o;
			}
		return null;
	}

	int ownStaticSlot(String name, String desc) {
		Integer s = staticFieldSlots.get(name + "|" + desc);
		return s == null ? -1 : s;
	}

	/** number of arguments in a method descriptor (wide counts once), excl. receiver. */
	static int countArgs(String desc) {
		int n = 0;
		for (int i = 1; desc.charAt(i) != ')'; i++) {
			char c = desc.charAt(i);
			if (c == '[') {
				while (desc.charAt(i) == '[') i++;
				if (desc.charAt(i) == 'L') i = desc.indexOf(';', i);
			} else if (c == 'L') {
				i = desc.indexOf(';', i);
			}
			n++;
		}
		return n;
	}

	/** count of argument slots consumed by a method descriptor (excl. receiver). */
	static int argSlots(String desc) {
		int n = 0;
		for (int i = 1; desc.charAt(i) != ')'; i++) {
			char c = desc.charAt(i);
			if (c == '[') {
				while (desc.charAt(i) == '[') i++;
				if (desc.charAt(i) == 'L') i = desc.indexOf(';', i);
				n++;
			} else if (c == 'L') {
				i = desc.indexOf(';', i);
				n++;
			} else {
				n++;
				if (c == 'J' || c == 'D') n++;
			}
		}
		return n;
	}

	static String returnType(String desc) {
		return desc.substring(desc.indexOf(')') + 1);
	}
}
