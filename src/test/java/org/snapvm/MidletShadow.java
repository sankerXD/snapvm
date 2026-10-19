package org.snapvm;

import javax.microedition.midlet.MIDlet;

/**
 * Test adapter shadow: a real MIDlet that stands in for a guest midlet object
 * and forwards lifecycle calls back into the VM. This is the pattern the
 * JL-Mod adapter uses for every host class a game may extend (MIDlet, Canvas,
 * CommandListener is interface-only so needs no shadow, etc).
 */
public final class MidletShadow extends MIDlet implements GuestShadow {
	private final Vm vm;
	private final VmObject guest;

	public MidletShadow(Vm vm, VmObject guest) {
		this.vm = vm;
		this.guest = guest;
	}

	public VmObject guest() {
		return guest;
	}

	// AMS-facing public wrappers (real AMS lives in the same package as MIDlet)
	public void launch() {
		startApp();
	}

	public void pause() {
		pauseApp();
	}

	public void destroy(boolean unconditional) {
		destroyApp(unconditional);
	}

	protected void startApp() {
		vm.callFromHost(guest, "startApp", "()V");
	}

	protected void pauseApp() {
		vm.callFromHost(guest, "pauseApp", "()V");
	}

	protected void destroyApp(boolean unconditional) {
		vm.callFromHost(guest, "destroyApp", "(Z)V", unconditional);
	}
}
