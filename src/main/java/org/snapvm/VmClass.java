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
		this.access = cf.access;
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
		if (!host && other.host) {
			// guest interface vs host object: only if interface is host-implemented? rare; be strict
			return false;
		}
		if (host) {
			// host target, guest source: only java.lang.Object works (handled above), or when the
			// guest class is a shadowed subclass (M2, hostShadow != null)
			return false;
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
		if (other.isArray() && (name.equals("java/lang/Cloneable") || name.equals("java/io/Serializable")))
			return true;
		return false;
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
			return isArray() && host == false && componentCompat(obj);
		}
		if (obj instanceof GuestClass)
			return name.equals("java/lang/Class") || name.equals("java/lang/Object");
		// host object
		if (host)
			return hostClass.isInstance(obj);
		return name.equals("java/lang/Object");
	}

	private boolean componentCompat(Object array) {
		// crude: treat guest array types only at Object level
		return name.equals("[Ljava/lang/Object;");
	}

	void link() {
		if (cf == null)
			return;
		if (cf.superName != null)
			superClass = vm.resolveClass(cf.superName);
		else if (!name.equals("java/lang/Object"))
			superClass = vm.resolveClass("java/lang/Object");
		if (superClass != null && superClass.host && !superClass.name.equals("java/lang/Object"))
			throw new VmError("guest class " + name + " extends host class " + superClass.name
					+ " (needs shadow-object support, M2)");
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
		statics = new Object[stat];
	}

	static boolean isWide(String desc) {
		return desc.equals("J") || desc.equals("D");
	}

	/** static/special resolution: find method in this class or guest supers. */
	ClassFile.Method findDeclared(String name, String desc) {
		for (VmClass c = this; c != null && !c.host; c = c.superClass) {
			ClassFile.Method m = c.declaredMethods.get(name + "|" + desc);
			if (m != null)
				return m;
		}
		// interface superinterfaces for invokeinterface
		if (interfaces != null)
			for (VmClass i : interfaces) {
				ClassFile.Method m = i.findDeclared(name, desc);
				if (m != null)
					return m;
			}
		return null;
	}

	/** resolve instance/virtual method: search this class's merged table (covers overrides). */
	ClassFile.Method findMethod(String name, String desc) {
		return methodMap.get(name + "|" + desc);
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
