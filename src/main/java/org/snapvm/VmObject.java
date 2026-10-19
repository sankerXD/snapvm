package org.snapvm;

/** An instance of a guest (interpreted) class. Host objects are just real Java objects. */
public final class VmObject {
	final VmClass clazz;
	final Object[] fields; // flattened slots incl. guest superclasses; wide types use 2 slots

	VmObject(VmClass clazz) {
		this.clazz = clazz;
		this.fields = new Object[clazz.instanceSlotCount];
	}

	public VmClass vmClass() {
		return clazz;
	}

	@Override
	public String toString() {
		return clazz.name + "@" + Integer.toHexString(System.identityHashCode(this));
	}
}
