package org.snapvm;

/**
 * Codec for a host object reachable from guest state that has no guest-side
 * data (Bitmaps, players, streams...). Registered per host class on the Vm;
 * lookups walk superclasses/interfaces, so a codec for a base type covers
 * subclasses. The returned record must itself serialize as snapshot values
 * (scalars, strings, arrays, guest refs) — it must NOT reference the host
 * object it describes (no self-cycles).
 */
public interface HostHandle {
	/** Capture a snapshot-serializable record for {@code hostObj}, or null to store just a tombstone. */
	Object capture(Vm vm, Object hostObj);

	/** Rebuild the host object (or a live placeholder) from the record. */
	Object revive(Vm vm, Object record);
}
