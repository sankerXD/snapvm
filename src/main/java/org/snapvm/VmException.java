package org.snapvm;

/**
 * Carries a guest-level throwable (host Throwable or VmObject) while unwinding
 * interpreted frames. Reaches the top only when uncaught.
 */
public class VmException extends RuntimeException {
	public final Object thrown; // Throwable host object or VmObject
	public String vmTrace = "";

	public VmException(Object thrown) {
		super(String.valueOf(thrown));
		this.thrown = thrown;
	}
}
