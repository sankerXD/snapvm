package org.snapvm;

import java.util.Map;

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
	/** Args passed to the first host-super <init> (recv at [0]); kept so host-side
	 * state that skipped construction (e.g. Throwable's message) stays recoverable. */
	Object[] superCtorArgs;
	Object exceptionCause;                 // set by Throwable.initCause
	String exceptionTrace;                 // guest stack captured at construction (printStackTrace)
	/** Vm.epoch at construction — stale-graph detection for callFromHost. */
	final int epoch;
	/**
	 * Identity token that survives {@link Vm#restore}: lazily assigned by
	 * {@link Vm#serialFor} the first time this object is proxied to host code,
	 * and written into its OBJ record so restore can alias the old serial to
	 * the revived object. Host-side invokers (interface proxies) hold the
	 * serial, not the object — calls re-resolve against
	 * {@code vm.liveBySerial} and thus keep working after restore.
	 */
	long serial;
	/** iface -> host proxy minted by HostBridge.proxyFor; lazily allocated so
	 *  callSerially-style churn reuses one proxy per (object, iface) instead
	 *  of leaking a fresh Proxy into {@code vm.proxies} on every event. */
	Map<Class<?>, Object> proxies;

	VmObject(VmClass clazz) {
		this.clazz = clazz;
		this.epoch = clazz.vm.epoch;
		this.fields = clazz.fieldDefaults != null
				? clazz.fieldDefaults.clone()
				: new Object[clazz.instanceSlotCount];
	}

	public VmClass vmClass() {
		return clazz;
	}

	/**
	 * The VM epoch this object was built in. After {@link Vm#restore} it is
	 * stale — calls into it are dropped by {@link Vm#callFromHost}. Adapter
	 * shadows can check this to self-disable instead of spamming drops.
	 */
	public boolean isStale() {
		return epoch != clazz.vm.epoch;
	}

	@Override
	public String toString() {
		return clazz.name + "@" + Integer.toHexString(System.identityHashCode(this));
	}
}
