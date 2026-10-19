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
	final List<Frame> frames = new ArrayList<>();
	int state = RUNNABLE;
	Thread hostThread;          // the java.lang.Thread object the guest sees
	Object runTarget;           // guest Runnable (proxy or VmObject) for Thread.start()
	long wakeTime;              // for sleep/timed wait
	Object waitMonitor;         // monitor being waited on / contended
	int waitReleasedCount;      // monitor count to restore after wait() returns
	boolean resumingFromWait;   // retried monEnter before resuming after wait/notify
	Object result;              // set when entry method returns
	Object nestedResult;        // nested callFromHost result
	VmException lastError;

	private final List<VmThread> joinWaiters = new ArrayList<>();

	VmThread(Vm vm) {
		this.vm = vm;
	}

	Frame top() {
		return frames.isEmpty() ? null : frames.get(frames.size() - 1);
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

	boolean isAlive() {
		return state != TERMINATED;
	}
}
