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
}
