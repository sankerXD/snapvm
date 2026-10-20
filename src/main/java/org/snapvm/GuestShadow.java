package org.snapvm;

/**
 * Implemented by adapter-side "shadow" instances: real host objects that stand
 * in for a guest object when the guest class extends a host class (e.g.
 * javax.microedition.midlet.MIDlet). The shadow's overriding methods typically
 * forward into {@link Vm#callFromHost}; the VM uses {@link #guest()} to unwrap
 * a shadow back to its guest object when it crosses the boundary again.
 */
public interface GuestShadow {
	VmObject guest();

	/**
	 * Swallow the next lifecycle notification (showNotify/hideNotify) instead
	 * of forwarding it into the guest. Used when a shadow is resurrected by a
	 * snapshot restore: the re-show is a host-side reconstruction, not a real
	 * hide→show transition — from the guest's perspective the canvas never
	 * left the screen. Games that auto-pause in showNotify/hideNotify would
	 * otherwise pop their pause UI on every load.
	 */
	default void suppressNextLifecycleNotify() {
	}
}
