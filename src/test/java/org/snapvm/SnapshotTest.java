package org.snapvm;

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Snapshot save/restore round-trips (docs/snapshot-format.md): freeze a live
 * machine mid-schedule, run it to an end state, restore, run again — the
 * revived machine must reach the identical state.
 */
public class SnapshotTest {

	private Map<String, byte[]> compile(String... sources) throws IOException {
		JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
		File dir = Files.createTempDirectory("vmtest").toFile();
		List<String> files = new ArrayList<>();
		int i = 0;
		for (String src : sources) {
			String name = src.contains("class ")
					? src.split("class ")[1].split("[ <{]")[0].trim() : "C" + i++;
			File f = new File(dir, name + ".java");
			Files.write(f.toPath(), src.getBytes("UTF-8"));
			files.add(f.getAbsolutePath());
		}
		List<String> args = new ArrayList<>();
		args.add("-source");
		args.add("8");
		args.add("-target");
		args.add("8");
		args.add("-d");
		args.add(dir.getAbsolutePath());
		args.addAll(files);
		int rc = jc.run(null, null, null, args.toArray(new String[0]));
		assertEquals("fixture compile failed", 0, rc);
		Map<String, byte[]> out = new HashMap<>();
		for (File f : dir.listFiles()) {
			if (!f.getName().endsWith(".class"))
				continue;
			out.put(f.getName().replace(".class", "").replace('.', '/'),
					Files.readAllBytes(f.toPath()));
		}
		return out;
	}

	private Vm vm(Map<String, byte[]> classes) {
		Vm vm = new Vm();
		vm.addClassSource(new Vm.ClassSource() {
			public byte[] findClass(String name) {
				return classes.get(name.replace('/', '.'));
			}

			public InputStream findResource(String path) {
				return null;
			}
		});
		return vm;
	}

	private Object run(Vm vm, String cls, String method) {
		return vm.invokeStatic(cls, method, "()I");
	}

	/** A midlet-shaped fixture: main entry returns while a worker green thread
	 *  is mid-loop; cont() joins it. Deterministic totals. */
	private Map<String, byte[]> workerFixture() throws IOException {
		return compile(
				"public class Sn {"
						+ " static int acc; static int[] arr; static Sn self; static Thread w;"
						+ " int inst = 7; long wide;"
						+ " static class W implements Runnable { public void run() {"
						+ "  for (int i = 1; i <= 6; i++) {"
						+ "   synchronized (self) { acc += i; arr[i % 4] += i; }"
						+ "   long l = self.wide; l += i; self.wide = l;" // wide locals survive
						+ "   try { Thread.sleep(2); } catch (InterruptedException e) {}"
						+ "  }"
						+ "  synchronized (self) { acc += 100; self.inst += 3; } } }"
						+ " public static int go() {"
						+ "  acc = 0; arr = new int[4]; self = new Sn();"
						+ "  w = new Thread(new W()); w.start();"
						+ "  synchronized (self) { acc += 10; arr[0] += 5; }"
						+ "  return acc; }"
						+ " public static int cont() {"
						+ "  synchronized (self) { acc += 2; arr[1] += 7; }"
						+ "  try { w.join(); } catch (InterruptedException e) { return -1; }"
						+ "  return acc * 1000 + self.inst; } }");
	}

	// The core promise: run → snapshot mid-schedule → run to end → restore →
	// same end state, twice (same-vm and fresh-vm restore).
	@Test
	public void roundTripResumesWorker() throws Exception {
		Map<String, byte[]> cls = workerFixture();
		Vm vm = vm(cls);
		run(vm, "Sn", "go");
		byte[] snap = vm.checkpoint();
		int a = (Integer) run(vm, "Sn", "cont");
		vm.restore(snap);
		int b = (Integer) run(vm, "Sn", "cont");
		assertEquals(a, b);
		// (10 + 2 + 1..6 + 100) * 1000 + (7 + 3)
		assertEquals(133010, b);

		Vm fresh = vm(cls);
		fresh.restore(snap);
		assertEquals(133010, run(fresh, "Sn", "cont"));
	}

	// Shared refs, cycles, interned strings, guest containers, array identity.
	@Test
	public void heapIdentityPreserved() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Hp {"
						+ " static Object[] box; static String s1; static Hp a;"
						+ " static java.util.Hashtable ht;"
						+ " Hp peer; int[] nums;"
						+ " public static int go() {"
						+ "  a = new Hp(); Hp b = new Hp(); a.peer = b; b.peer = a;"
						+ "  a.nums = new int[]{1, 2, 3};"
						+ "  box = new Object[]{a, b, a};"
						+ "  s1 = \"lit\".intern();"
						+ "  ht = new java.util.Hashtable(); ht.put(\"k\", a.nums);"
						+ "  return 0; }"
						+ " public static int check() {"
						+ "  return (box[0] == box[2] ? 1 : 0)"
						+ "       + (box[0] == a ? 2 : 0)"
						+ "       + (a.peer.peer == a ? 4 : 0)"
						+ "       + (s1 == \"lit\" ? 8 : 0)"
						+ "       + (ht.get(\"k\") == a.nums && a.nums[2] == 3 ? 16 : 0); } }");
		Vm vm = vm(cls);
		run(vm, "Hp", "go");
		assertEquals(31, run(vm, "Hp", "check"));
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(31, run(vm, "Hp", "check"));
	}

	// A thread parked inside wait() comes back WAITING on a rebuilt monitor;
	// notify() from another entry call wakes it and it reacquires cleanly.
	@Test
	public void waitingThreadRestoredWaiting() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Wt {"
						+ " static final Object L = new Object();"
						+ " static int r; static Thread w;"
						+ " static class W implements Runnable { public void run() {"
						+ "  synchronized (L) {"
						+ "   try { L.wait(); } catch (InterruptedException e) { r = -9; }"
						+ "   r = 42; } } }"
						+ " public static int go() {"
						+ "  w = new Thread(new W()); w.start();"
						+ "  try { Thread.sleep(30); } catch (InterruptedException e) {}"
						+ "  return 0; }"
						+ " public static int poke() {"
						+ "  synchronized (L) { L.notify(); }"
						+ "  try { w.join(); } catch (InterruptedException e) { return -1; }"
						+ "  return r; } }");
		Vm vm = vm(cls);
		run(vm, "Wt", "go"); // worker reaches L.wait() while main sleeps
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(42, run(vm, "Wt", "poke"));
	}

	// A thread BLOCKED on monitor entry serializes its waitMonitor; the
	// per-monitor contenders list must be rebuilt on restore or the owner's
	// monExit wakes nobody and a join() deadlocks. Covers the contenders
	// queue that replaced the old O(threads) wake scan in monExit.
	@Test
	public void blockedContenderRestored() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Bc {"
						+ " static final Object L = new Object();"
						+ " static int r; static Thread w;"
						+ " static class H implements Runnable { public void run() {"
						+ "  synchronized (L) {"
						+ "   try { Thread.sleep(60); } catch (InterruptedException e) {}"
						+ "  } } }"               // holds L while sleeping
						+ " static class W implements Runnable { public void run() {"
						+ "  synchronized (L) { r = 7; } } }" // blocks until H exits
						+ " public static int go() {"
						+ "  new Thread(new H()).start();"
						+ "  w = new Thread(new W()); w.start();"
						+ "  try { Thread.sleep(20); } catch (InterruptedException e) {}"
						+ "  return 0; }"         // returns with W BLOCKED on L
						+ " public static int poke() {"
						+ "  try { w.join(); } catch (InterruptedException e) { return -1; }"
						+ "  return r; } }");
		Vm vm = vm(cls);
		run(vm, "Bc", "go"); // H holds L (asleep inside sync), W is BLOCKED on it
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(7, run(vm, "Bc", "poke"));
	}

	// A VmObject held across restore belongs to the discarded graph — but
	// statics live in the SHARED VmClass, so forwarding a queued host callback
	// (e.g. a detached Canvas shadow's hideNotify) into it would scribble on
	// LIVE class state. callFromHost drops stale-epoch receivers instead.
	@Test
	public void staleObjectCallsDropped() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class St {"
						+ " static int marker; static St last;"
						+ " public static Object make() { return last = new St(); }"
						+ " void poke() { marker = 7; }"
						+ " public static int check() { return marker; }"
						+ " public static int pokeLive() { last.poke(); return marker; } }");
		Vm vm = vm(cls);
		Object stale = vm.invokeStatic("St", "make", "()Ljava/lang/Object;");
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertNull(vm.callFromHost(stale, "poke", "()V"));
		assertEquals(0, run(vm, "St", "check")); // stale poke() never ran
		assertEquals(7, run(vm, "St", "pokeLive")); // restored receiver unaffected
	}

	// A host-side Runnable proxy (the shape event queues retain for
	// callSerially) holds only a serial token: after restore it re-resolves to
	// the revived guest object, so queued callbacks keep delivering. A proxy
	// whose guest never entered the snapshot resolves to nothing and drops.
	@Test
	public void proxyRebindsAcrossRestore() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Pr implements Runnable {"
						+ " static int hits; static Pr last;"
						+ " public void run() { hits++; }"
						+ " public static Object make() { return last = new Pr(); }"
						+ " public static int check() { return hits; } }");
		Vm vm = vm(cls);
		VmObject g = (VmObject) vm.invokeStatic("Pr", "make", "()Ljava/lang/Object;");
		Runnable r = (Runnable) vm.bridge.proxyFor(g, Runnable.class);
		Runnable dead = (Runnable) vm.bridge.proxyFor(
				vm.newGuestObject("Pr"), Runnable.class);
		vm.proxies.remove(dead); // event discarded pre-save: serial never serialized
		r.run();
		assertEquals(1, run(vm, "Pr", "check"));
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		r.run();    // old proxy object, revived guest — must deliver
		assertEquals(2, run(vm, "Pr", "check"));
		dead.run(); // serial absent from liveBySerial — silent drop, no throw
		assertEquals(2, run(vm, "Pr", "check"));
	}

	// 3D迷你高尔夫 white screen: the game's Display.callSerially loop is
	// carried by host-side events — each pops a serial proxy which enters the
	// guest on a NEW entry thread via runUntilDone. A restore landing while
	// that entry thread was asleep used to orphan it (threads.clear), making
	// runUntilDone abandon the call: the RunnableEvent completed without
	// re-posting and the serial chain died permanently. Threads born after
	// the save (index >= nt) are kept across the wipe instead.
	@Test
	public void inflightHostCallSurvivesRestore() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Rp implements Runnable {"
						+ " static int runs; static Rp last;"
						+ " public void run() {"
						+ "  try { Thread.sleep(150); } catch (InterruptedException e) {}"
						+ "  runs++; }"
						+ " public static Object make() { return last = new Rp(); }"
						+ " public static int check() { return runs; } }");
		Vm vm = vm(cls);
		VmObject g = (VmObject) vm.invokeStatic("Rp", "make", "()Ljava/lang/Object;");
		final Runnable r = (Runnable) vm.bridge.proxyFor(g, Runnable.class);
		byte[] snap = vm.checkpoint();

		// The event queue fires the proxy on its own thread; run() sleeps
		// inside, so the callback is in-flight when the restore lands.
		Thread host = new Thread(new Runnable() {
			public void run() {
				r.run();
			}
		});
		host.start();
		Thread.sleep(40);
		vm.restore(snap);
		host.join(10000);
		assertFalse(host.isAlive());

		assertEquals(1, run(vm, "Rp", "check")); // in-flight call completed
		r.run();                                  // serial re-binds post-restore
		assertEquals(2, run(vm, "Rp", "check"));
	}

	// The dual case: an entry thread already in-flight AT save time is covered
	// by the snapshot — the revived copy owns the continuation. The stale
	// original must not also finish, or the callback body would run twice.
	@Test
	public void coveredInflightCallNotDoubleRun() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Rq implements Runnable {"
						+ " static int runs; static Rq last;"
						+ " public void run() {"
						+ "  try { Thread.sleep(150); } catch (InterruptedException e) {}"
						+ "  runs++; }"
						+ " public static Object make() { return last = new Rq(); }"
						+ " public static int check() { return runs; } }");
		Vm vm = vm(cls);
		VmObject g = (VmObject) vm.invokeStatic("Rq", "make", "()Ljava/lang/Object;");
		final Runnable r = (Runnable) vm.bridge.proxyFor(g, Runnable.class);

		Thread host = new Thread(new Runnable() {
			public void run() {
				r.run();
			}
		});
		host.start();
		Thread.sleep(40);
		byte[] snap = vm.checkpoint(); // entry thread captured mid-sleep
		vm.restore(snap);              // original orphaned; revived copy resumes
		host.join(10000);
		assertFalse(host.isAlive());

		long deadline = System.currentTimeMillis() + 3000;
		while (System.currentTimeMillis() < deadline
				&& (Integer) run(vm, "Rq", "check") == 0)
			vm.pump(20);
		assertEquals(1, run(vm, "Rq", "check")); // revived copy ran it exactly once
	}

	// Golf's snapshot grew ~50B per host event: every callSerially/entry call
	// leaves a TERMINATED corpse in threads[] forever. cullDeadThreads keeps
	// only threads the graph can still observe (live, box-bound, join-linked),
	// so a long session stays a handful of records instead of tens of thousands.
	@Test
	public void deadEntryThreadsCulled() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Ck {"
						+ " static int n;"
						+ " public static int go() { return ++n; } }");
		Vm vm = vm(cls);
		for (int i = 0; i < 1100; i++) // > sweep interval: exercises auto-cull too
			run(vm, "Ck", "go");
		byte[] snap = vm.checkpoint(); // culls before writing
		synchronized (vm.threads) {
			for (VmThread t : vm.threads)
				assertTrue("corpse survived cull", t.isAlive());
		}
		assertTrue("snapshot carrying corpses: " + snap.length,
				snap.length < 20000);
		vm.restore(snap);
		assertEquals(1101, run(vm, "Ck", "go"));
	}

	// A green thread started between save and restore belongs to the
	// discarded graph — letting it run would touch stale objects. Restore
	// terminates it; a live host-entry call (in-flight callback) is the only
	// post-save thread that survives.
	@Test
	public void postSaveGreenThreadTerminated() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Gt {"
						+ " static Thread w; static int flag;"
						+ " static class W implements Runnable { public void run() {"
						+ "  try { Thread.sleep(60000); } catch (InterruptedException e) {}"
						+ "  flag = 1; } }"
						+ " public static int start() {"
						+ "  w = new Thread(new W()); w.start(); return 0; }"
						+ " public static int alive() { return w.isAlive() ? 1 : 0; } }");
		Vm vm = vm(cls);
		byte[] snap = vm.checkpoint();
		run(vm, "Gt", "start");
		Thread.sleep(30);
		assertEquals(1, run(vm, "Gt", "alive"));
		VmThread green = null;
		synchronized (vm.threads) {
			for (VmThread t : vm.threads)
				if (!t.hostEntry && t.isAlive())
					green = t;
		}
		assertNotNull(green);
		vm.restore(snap);
		assertFalse("post-save green thread kept its stale frames", green.isAlive());
	}

	// callSerially re-proxies the same Runnable every tick — without the
	// (guest, iface) cache each call minted a fresh Proxy that stayed in
	// vm.proxies forever, flooding both host memory and the snapshot's
	// proxy table. One proxy per pair now, and it survives restore.
	@Test
	public void proxyPerGuestIfacePair() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Pd implements Runnable {"
						+ " static int hits; static Pd last;"
						+ " public void run() { hits++; }"
						+ " public static Object make() { return last = new Pd(); }"
						+ " public static Object lastObj() { return last; }"
						+ " public static int check() { return hits; } }");
		Vm vm = vm(cls);
		VmObject g = (VmObject) vm.invokeStatic("Pd", "make", "()Ljava/lang/Object;");
		Object p1 = vm.bridge.proxyFor(g, Runnable.class);
		Object p2 = vm.bridge.proxyFor(g, Runnable.class);
		assertSame("proxy not deduped", p1, p2);
		assertEquals(1, vm.proxies.size());
		((Runnable) p1).run();
		assertEquals(1, run(vm, "Pd", "check"));

		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		// readProxies recreates the pair — same cache slot on the revived object
		VmObject g2 = (VmObject) vm.invokeStatic("Pd", "lastObj", "()Ljava/lang/Object;");
		Object p3 = vm.bridge.proxyFor(g2, Runnable.class);
		assertNotSame(p1, p3);
		assertEquals(1, vm.proxies.size());
		((Runnable) p3).run();
		assertEquals(2, run(vm, "Pd", "check"));
	}

	// TIMED_WAITING comes back still asleep (remaining time), and interrupt()
	// on the restored thread delivers the exception at the sleep call site.
	@Test
	public void sleepTimerPreserved() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Sl {"
						+ " static int r; static Thread w;"
						+ " static class W implements Runnable { public void run() {"
						+ "  try { Thread.sleep(60000); r = 1; }"
						+ "  catch (InterruptedException e) { r = 7; } } }"
						+ " public static int go() {"
						+ "  w = new Thread(new W()); w.start();"
						+ "  try { Thread.sleep(30); } catch (InterruptedException e) {}"
						+ "  return 0; }"
						+ " public static int fin() {"
						+ "  w.interrupt();"
						+ "  try { w.join(); } catch (InterruptedException e) { return -1; }"
						+ "  return r; } }");
		Vm vm = vm(cls);
		run(vm, "Sl", "go");
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(7, run(vm, "Sl", "fin"));
	}

	// A thread parked inside a host call (waitMonitor==PARKED) can't carry its
	// done-callback through bytes — restore replays the parked call instead.
	@Test
	public void parkedCallReplayed() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Pc {"
						+ " static int r; static Thread w;"
						+ " static class W implements Runnable { public void run() {"
						+ "  new Object().toString(); r = 7; } }"
						+ " public static int go() {"
						+ "  w = new Thread(new W()); w.start();"
						+ "  try { Thread.sleep(30); } catch (InterruptedException e) {}"
						+ "  return 0; }"
						+ " public static int fin() {"
						+ "  try { w.join(); } catch (InterruptedException e) { return -1; }"
						+ "  return r; } }");
		List<Runnable> dones = new ArrayList<>();
		Vm vm = vm(cls);
		vm.registerParkedCall("java/lang/Object.toString()Ljava/lang/String;",
				(v, t, args, done) -> dones.add(done));
		run(vm, "Pc", "go");
		assertEquals(1, dones.size()); // worker parked inside toString
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(2, dones.size()); // restore replayed the parked call
		dones.get(1).run(); // host side completes → worker resumes
		assertEquals(7, run(vm, "Pc", "fin"));
	}

	// Reference arrays must revive with their real runtime type, not a bare
	// Object[] — DNF kept int[][] tables inside a Vector and every post-
	// restore checkcast [[I died (invisible character). Guest-component
	// arrays still resolve through guestArrayTypes.
	@Test
	public void typedArraysKeepRuntimeType() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Ta {"
						+ " static java.util.Vector v; static String[] ss;"
						+ " static int[][] grid; static Ta[] ta;"
						+ " public static int go() {"
						+ "  v = new java.util.Vector();"
						+ "  v.addElement(new int[][]{{1, 2}, {3}});"
						+ "  ss = new String[]{\"a\", \"b\"};"
						+ "  grid = new int[2][];"
						+ "  grid[0] = new int[]{9}; grid[1] = new int[]{8, 7};"
						+ "  ta = new Ta[2]; ta[0] = new Ta();"
						+ "  return 0; }"
						+ " public static int check() {"
						+ "  int[][] a = (int[][]) v.elementAt(0);"   // checkcast [[I
						+ "  Object o = ss; String[] s = (String[]) o;" // checkcast [Ljava/lang/String;
						+ "  int[][] g = (int[][]) grid;"
						+ "  Ta[] t = (Ta[]) ta;"                      // guest-component array
						+ "  return a[1][0] + s.length + g[1][1] + t.length; } }");
		Vm vm = vm(cls);
		run(vm, "Ta", "go");
		assertEquals(14, run(vm, "Ta", "check")); // pre-restore sanity
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(14, run(vm, "Ta", "check"));

		Vm fresh = vm(cls);
		fresh.restore(snap);
		assertEquals(14, run(fresh, "Ta", "check"));
	}

	// Host objects with no registered codec must abort the save loudly —
	// a half-written world is worse than a refused one.
	@Test
	public void unhandledHostObjectFailsSave() throws Exception {
		Vm vm = vm(compile(
				"public class Hd { static java.util.HashMap m;"
						+ " public static int go() { m = new java.util.HashMap(); return 0; } }"));
		run(vm, "Hd", "go");
		try {
			vm.checkpoint();
			fail("expected VmError");
		} catch (VmError e) {
			assertTrue(e.getMessage().contains("java.util.HashMap"));
		}
	}

	// Date and ByteArrayInputStream are pure-JCL state — the VM ships codecs.
	@Test
	public void defaultHandlesCoverCommonHostState() throws Exception {
		Vm vm = vm(compile(
				"public class Dh {"
						+ " static java.util.Date d; static java.io.InputStream in;"
						+ " public static int go() throws Exception {"
						+ "  d = new java.util.Date(86400000);"
						+ "  in = new java.io.ByteArrayInputStream(new byte[]{9, 8, 7});"
						+ "  in.read();" // consume one byte — position mid-stream
						+ "  return 0; }"
						+ " public static int check() throws Exception {"
						+ "  return (d.getTime() == 86400000 ? 1 : 0)"
						+ "       + (in.read() == 8 && in.read() == 7 ? 2 : 0); } }"));
		run(vm, "Dh", "go");
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(3, run(vm, "Dh", "check"));
	}

	// Rainbow Six Vegas parks a jar-resource stream inside a static
	// DataInputStream between level loads — the wrapper has no buffering,
	// so the codec captures FilterInputStream.in and the wrapped
	// ByteArrayInputStream travels through its own handle. Both a raw
	// InputStream field and the wrapper sit in guest statics here, the
	// game's exact shape.
	@Test
	public void dataInputStreamRoundTrip() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Di {"
						+ " static java.io.InputStream raw; static java.io.DataInputStream dis;"
						+ " public static int go() throws Exception {"
						+ "  raw = new java.io.ByteArrayInputStream("
						+ "      new byte[]{0, 0, 0, 1, 0, 0, 0, 2, 9});"
						+ "  dis = new java.io.DataInputStream(raw);"
						+ "  return dis.readInt();"              // consumes 4 of 9
						+ " }"
						+ " public static int check() throws Exception {"
						+ "  return dis.readInt() * 100 + dis.read();" // 2*100 + 9
						+ " } }");
		Vm vm = vm(cls);
		assertEquals(1, run(vm, "Di", "go"));
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(209, run(vm, "Di", "check"));

		Vm fresh = vm(cls);
		fresh.restore(snap);
		assertEquals(209, run(fresh, "Di", "check"));
	}

	// A host thread can slip a newThread() between the tid map build and
	// writeThreads (newThread locks `threads`, not execLock) — threads born
	// mid-write must be skipped, not NPE.
	@Test
	public void threadBornMidCheckpointSkipped() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Lb {"
						+ " static StringBuilder box;"
						+ " public static int go() { box = new StringBuilder(); return 0; } }");
		Vm vm = vm(cls);
		final Vm vmF = vm;
		vm.registerHostHandle(StringBuilder.class, new HostHandle() {
			public Object capture(Vm v, Object h) {
				vmF.newThread(); // sneaks in after the tid map is built
				return null;
			}

			public Object revive(Vm v, Object rec) {
				return new StringBuilder();
			}
		});
		run(vm, "Lb", "go");
		byte[] snap = vm.checkpoint(); // used to NPE in writeThreads
		vm.restore(snap);
	}

	// 3D足球 parks a static ByteArrayOutputStream for resource staging —
	// codec round-trips the buffered bytes and keeps the stream writable.
	@Test
	public void byteArrayOutputStreamRoundTrip() throws Exception {
		Map<String, byte[]> cls = compile(
				"public class Bo {"
						+ " static java.io.ByteArrayOutputStream bos;"
						+ " public static int go() throws Exception {"
						+ "  bos = new java.io.ByteArrayOutputStream();"
						+ "  bos.write(5); bos.write(6);"
						+ "  return bos.size(); }"
						+ " public static int check() throws Exception {"
						+ "  bos.write(7);"
						+ "  byte[] b = bos.toByteArray();"
						+ "  return b.length * 100 + b[0] * 10 + b[2]; } }");
		Vm vm = vm(cls);
		assertEquals(2, run(vm, "Bo", "go"));
		byte[] snap = vm.checkpoint();
		vm.restore(snap);
		assertEquals(357, run(vm, "Bo", "check")); // len 3, b[0]=5, b[2]=7
	}
}
