package org.snapvm;

/** An instance of a guest (interpreted) class. Host objects are just real Java objects. */
public final class VmObject {
	public final VmClass clazz;
	public final Object[] fields;
	/**
	 * Adapter-owned host instance representing this guest object (set when a
	 * guest class extends a host class — e.g. a midlet shadow). Used when the
	 * object is marshaled to a host API expecting the real class.
	 */
	public Object hostShadow;

	VmObject(VmClass clazz) {
		this.clazz = clazz;
		this.fields = clazz.fieldDefaults != null
				? clazz.fieldDefaults.clone()
				: new Object[clazz.instanceSlotCount];
	}

	public VmClass vmClass() {
		return clazz;
	}

	@Override
	public String toString() {
		return clazz.name + "@" + Integer.toHexString(System.identityHashCode(this));
	}
}
