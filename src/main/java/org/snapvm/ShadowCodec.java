package org.snapvm;

/**
 * State codec for a {@link Vm.ShadowFactory}'s product. When a guest object
 * extends a host class, its {@code hostShadow} is recreated on restore by the
 * factory — this codec is what folds the captured host-side state back in so
 * the shadow resumes where it was (canvas contents, player position, ...).
 * Registered per host base class, matching the factory's registration.
 * The record serializes as snapshot values; it must not reference the shadow.
 */
public interface ShadowCodec {
	/** Capture host-side state worth saving, or null when the shadow is stateless. */
	Object capture(Vm vm, VmObject guest, Object shadow);

	/** Restore a fresh shadow (just built by the ShadowFactory) from the record. */
	void restore(Vm vm, VmObject guest, Object shadow, Object record);
}
