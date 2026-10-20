package org.snapvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wall-clock stack sampler: "where does interpreter time go" in one window.
 *
 * Every {@link #intervalMs} it walks all live threads, keeps the ones whose
 * stack touches {@code org.snapvm} frames, and histograms the leaf frames.
 * Every {@link #windowMs} it emits the top entries plus a coarse category
 * rollup through the configured {@link Sink}. When a sampled thread is the
 * VM executor it also appends the running guest frame, so a hot leaf reads
 * as e.g. {@code Interpreter.step <= ... | guest AnimationLoader.getElement}.
 *
 * <p>Zero cost while stopped — no hooks inside the interpreter. Sample
 * overhead is one {@code getAllStackTraces} per interval; keep the cadence
 * sane (&ge;5ms) on low-end devices.
 *
 * <p>Usage:
 * <pre>
 *   VmSampler s = new VmSampler(vm, line -&gt; Log.i("SnapVm", line));
 *   s.start();   // toggle from a marker file / debug flag
 *   s.stop();
 * </pre>
 */
public final class VmSampler implements Runnable {

	/** Receives one dump line per call — wire to Log.i, stderr, a file... */
	public interface Sink {
		void log(String line);
	}

	private final Vm vm;
	private final Sink sink;
	private volatile boolean running;
	private Thread thread;
	private int intervalMs = 9;
	private int windowMs = 5000;
	private int top = 12;

	public VmSampler(Vm vm, Sink sink) {
		this.vm = vm;
		this.sink = sink;
	}

	public VmSampler(Vm vm) {
		this(vm, System.err::println);
	}

	public VmSampler intervalMs(int ms) {
		intervalMs = Math.max(1, ms);
		return this;
	}

	public VmSampler windowMs(int ms) {
		windowMs = Math.max(200, ms);
		return this;
	}

	public VmSampler top(int n) {
		top = Math.max(1, n);
		return this;
	}

	public void start() {
		if (running)
			return;
		running = true;
		thread = new Thread(this, "VmSampler");
		thread.setDaemon(true);
		thread.start();
	}

	public void stop() {
		running = false;
	}

	public boolean isRunning() {
		return running;
	}

	public void run() {
		Map<String, Integer> counts = new HashMap<>();
		Map<String, Integer> cats = new HashMap<>();
		int samples = 0;
		long window = System.currentTimeMillis();
		while (running) {
			try {
				Thread.sleep(intervalMs);
			} catch (InterruptedException ignored) {
			}
			samples += sampleOnce(counts, cats);
			long now = System.currentTimeMillis();
			if (now - window >= windowMs) {
				dump(counts, cats, samples, now - window);
				counts.clear();
				cats.clear();
				samples = 0;
				window = now;
			}
		}
	}

	/**
	 * One sampling round across all threads: leaf-frame keys into {@code counts},
	 * category rollups into {@code cats}. Returns the number of VM-touching
	 * thread stacks seen this round. Exposed for manual/one-shot use.
	 */
	public int sampleOnce(Map<String, Integer> counts, Map<String, Integer> cats) {
		Thread exec = vm.executorThread;
		VmThread cur = vm.executor;
		int n = 0;
		for (Map.Entry<Thread, StackTraceElement[]> e
				: Thread.getAllStackTraces().entrySet()) {
			if (e.getKey() == thread)
				continue; // sampler's own introspection is noise
			StackTraceElement[] st = e.getValue();
			if (st.length == 0 || !touchesVm(st))
				continue;
			n++;
			StringBuilder k = new StringBuilder(e.getKey().getName());
			for (int i = 0; i < 3 && i < st.length; i++) {
				String cn = st[i].getClassName();
				cn = cn.substring(cn.lastIndexOf('.') + 1);
				k.append(i == 0 ? " @ " : "<=").append(cn)
						.append('.').append(st[i].getMethodName())
						.append(':').append(st[i].getLineNumber());
			}
			if (e.getKey() == exec && cur != null) {
				try {
					Frame f = cur.top();
					if (f != null)
						k.append(" | guest ").append(f.describe());
				} catch (Throwable ignored) {
					// frame list mid-mutation — skip attribution this round
				}
			}
			String key = k.toString();
			counts.put(key, counts.containsKey(key) ? counts.get(key) + 1 : 1);
			String c = category(st);
			cats.put(c, cats.containsKey(c) ? cats.get(c) + 1 : 1);
		}
		return n;
	}

	private static boolean touchesVm(StackTraceElement[] st) {
		for (StackTraceElement el : st)
			if (el.getClassName().startsWith("org.snapvm."))
				return true;
		return false;
	}

	/**
	 * Coarse bucket for the rollup line. Leaf idle/native-io wins, else the
	 * FIRST org.snapvm frame decides (a HashMap.get inside monExit counts as
	 * monitor time, not HashMap time).
	 */
	private static String category(StackTraceElement[] st) {
		StackTraceElement leaf = st[0];
		String sn = leaf.getClassName();
		sn = sn.substring(sn.lastIndexOf('.') + 1)
				+ "." + leaf.getMethodName();
		if (sn.equals("Thread.sleep") || sn.equals("Object.wait"))
			return "idle";
		if (leaf.isNativeMethod()
				|| sn.startsWith("Linux.") || sn.startsWith("ForwardingOs.")
				|| sn.startsWith("BlockGuardOs.") || sn.startsWith("IoBridge.")
				|| sn.startsWith("File.") || sn.startsWith("UnixFileSystem.")
				|| sn.startsWith("RandomAccessFile."))
			return "native/io";
		for (StackTraceElement el : st) {
			String cn = el.getClassName();
			if (!cn.startsWith("org.snapvm."))
				continue;
			String mn = el.getMethodName();
			if (cn.endsWith("Vm") && (mn.startsWith("mon") || mn.equals("monitorOf")
					|| mn.equals("wakeContender") || mn.startsWith("objW")
					|| mn.startsWith("objN") || mn.equals("releaseMonitors")))
				return "monitor";
			if (cn.endsWith("HostBridge") || cn.endsWith("Vm")
					&& (mn.startsWith("parked") || mn.startsWith("fastCall")
					|| mn.startsWith("callParked") || mn.equals("call")))
				return "host-call";
			if (cn.endsWith("Interpreter"))
				return "interp";
			if (cn.endsWith("Vm") && (mn.equals("pump") || mn.equals("nextRunnable")
					|| mn.equals("runSlice")))
				return "sched";
			return "vm-other";
		}
		return "other";
	}

	private void dump(Map<String, Integer> counts, Map<String, Integer> cats,
			int samples, long ms) {
		List<Map.Entry<String, Integer>> topList = new ArrayList<>(counts.entrySet());
		topList.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		StringBuilder sb = new StringBuilder(
				"VmSampler " + samples + " samples/" + ms + "ms |");
		List<Map.Entry<String, Integer>> cl = new ArrayList<>(cats.entrySet());
		cl.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		for (Map.Entry<String, Integer> e : cl)
			sb.append(' ').append(e.getKey()).append(' ')
					.append(samples == 0 ? 0 : e.getValue() * 100 / Math.max(1, samples))
					.append('%');
		sink.log(sb.toString());
		int shown = 0;
		for (Map.Entry<String, Integer> en : topList) {
			if (shown++ >= top)
				break;
			sink.log("  " + en.getValue() + " " + en.getKey());
		}
	}
}
