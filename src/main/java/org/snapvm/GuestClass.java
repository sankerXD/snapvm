package org.snapvm;

/** Guest-visible java.lang.Class: wraps a VmClass. Host classes map to real java.lang.Class. */
public final class GuestClass {
	final VmClass cls;

	GuestClass(VmClass cls) {
		this.cls = cls;
	}

	public VmClass vmClass() {
		return cls;
	}

	@Override
	public String toString() {
		return (cls.isInterface() ? "interface " : "class ") + cls.name.replace('/', '.');
	}
}
