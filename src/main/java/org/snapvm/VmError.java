package org.snapvm;

/** VM-side failure (unsupported bytecode, verify error, marshaling gap). Not a guest exception. */
public class VmError extends Error {
	public VmError(String msg) {
		super(msg);
	}

	public VmError(String msg, Throwable cause) {
		super(msg, cause);
	}
}
