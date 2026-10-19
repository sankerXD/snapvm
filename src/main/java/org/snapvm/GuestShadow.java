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
}
