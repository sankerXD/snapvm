package org.snapvm;

import java.util.ArrayList;
import java.util.List;

/**
 * A green thread: a stack of interpreted frames plus scheduler state.
 * All guest threads are executed cooperatively on the single VM executor thread,
 * which makes the whole machine state serializable data.
 */
public final class VmThread {
	static final int RUNNABLE = 0, BLOCKED = 1, WAITING = 2, TIMED_WAITING = 3, TERMINATED = 4;

	final Vm vm;
	final int epoch;            // Vm.epoch at construction — stale-thread detection
	long serial;                // cross-restore identity; written in v3+ snapshots
	final List<Frame> frames = new ArrayList<>();
	int state = RUNNABLE;
	Thread hostThread;          // the java.lang.Thread object the guest sees
	Object runTarget;           // guest Runnable (proxy or VmObject) for Thread.start()
	boolean hostEntry;          // a host thread is blocked in runUntilDone on this thread
	boolean restoreOrphan;      // restore superseded it: a revived copy owns the continuation
	long wakeTime;              // for sleep/timed wait
	Object waitMonitor;         // monitor being waited on / contended
	int waitReleasedCount;      // monitor count to restore after wait() returns
	boolean resumingFromWait;   // retried monEnter before resuming after wait/notify
	boolean interrupted;        // JVM interrupt status
	boolean pendingInterrupt;   // deliver InterruptedException at interruptPc on resume
	int interruptPc = -1;       // pc of the interruptible call (invoke site), for handler lookup
	VmThread joinTarget;        // thread this one is join()-parked on (waitMonitor==null WAITING)
	String parkedCallKey;       // registered ParkedCall key while waitMonitor==PARKED (snapshot replays it)
	Object[] parkedArgs;        // guest args of the in-flight parked call
	Object result;              // set when entry method returns
	Object nestedResult;        // nested callFromHost result
	VmException nestedError;    // uncaught error stopped at a nested-call depth boundary
	VmException lastError;

	final List<VmThread> joinWaiters = new ArrayList<>();

	VmThread(Vm vm) {
		this.vm = vm;
		this.epoch = vm.epoch;
		this.serial = vm.nextThreadSerial++;
	}

	Frame top() {
		return frames.isEmpty() ? null : frames.get(frames.size() - 1);
	}

	/** host thread this green thread's entry call was made on (for parked calls). */
	public Thread hostThread() {
		return hostThread;
	}

	void terminate() {
		state = TERMINATED;
		vm.releaseMonitors(this);
		synchronized (joinWaiters) {
			for (VmThread t : joinWaiters)
				vm.wake(t);
			joinWaiters.clear();
		}
	}

	void addJoinWaiter(VmThread t) {
		synchronized (joinWaiters) {
			joinWaiters.add(t);
		}
	}

	void removeJoinWaiter(VmThread t) {
		synchronized (joinWaiters) {
			joinWaiters.remove(t);
		}
	}

	boolean isAlive() {
		return state != TERMINATED;
	}
}
