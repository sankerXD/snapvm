package org.snapvm;

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class InterpreterTest {

	/** Host-side probe class the guest code calls back into. */
	public static class Probe {
		public static final List<String> out = new ArrayList<>();
		public static int marker;

		public static void print(String s) {
			out.add(s);
		}

		public static int add(int a, int b) {
			return a + b;
		}
	}

	private Map<String, byte[]> compile(String... sources) throws IOException {
		JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
		File dir = Files.createTempDirectory("vmtest").toFile();
		List<String> files = new ArrayList<>();
		int i = 0;
		for (String src : sources) {
			String name = src.contains("class ") ?
					src.split("class ")[1].split("[ <{]")[0].trim() : "C" + i++;
			File f = new File(dir, name + ".java");
			Files.write(f.toPath(), src.getBytes("UTF-8"));
			files.add(f.getAbsolutePath());
		}
		List<String> args = new ArrayList<>();
		args.add("-source"); args.add("8");
		args.add("-target"); args.add("8");
		args.add("-d"); args.add(dir.getAbsolutePath());
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

	@Test
	public void arithmetic() throws Exception {
		Vm vm = vm(compile(
				"public class Calc { public static int go() { int s = 0; "
						+ "for (int i = 1; i <= 10; i++) s += i; return s; } }"));
		assertEquals(55, run(vm, "Calc", "go"));
	}

	@Test
	public void branches() throws Exception {
		Vm vm = vm(compile(
				"public class Br { public static int go(int n) { if (n < 0) return -1; "
						+ "switch (n) { case 3: return 30; case 5: return 50; default: return n; } } }"));
		assertEquals(30, vm.invokeStatic("Br", "go", "(I)I", 3));
		assertEquals(50, vm.invokeStatic("Br", "go", "(I)I", 5));
		assertEquals(7, vm.invokeStatic("Br", "go", "(I)I", 7));
		assertEquals(-1, vm.invokeStatic("Br", "go", "(I)I", -2));
	}

	@Test
	public void stringBuilder() throws Exception {
		Vm vm = vm(compile(
				"public class Sb { public static int go() { "
						+ "String s = \"x\" + 42 + true; return s.length(); } }"));
		assertEquals(7, run(vm, "Sb", "go"));
	}

	@Test
	public void hostCall() throws Exception {
		org.snapvm.InterpreterTest.Probe.out.clear();
		Vm vm = vm(compile(
				"public class Hc { public static int go() { "
						+ "org.snapvm.InterpreterTest.Probe.print(\"hi\"); return org.snapvm.InterpreterTest.Probe.add(20, 22); } }"));
		assertEquals(42, run(vm, "Hc", "go"));
		assertEquals(java.util.Arrays.asList("hi"), org.snapvm.InterpreterTest.Probe.out);
	}

	@Test
	public void objects() throws Exception {
		Vm vm = vm(compile(
				"public class Pt { int x, y; Pt(int x, int y){this.x=x;this.y=y;} int sum(){return x+y;} }",
				"public class Ob { public static int go() { Pt p = new Pt(3, 4); return p.sum(); } }"));
		assertEquals(7, run(vm, "Ob", "go"));
	}

	@Test
	public void inheritance() throws Exception {
		Vm vm = vm(compile(
				"public class An { int v() { return 1; } int base() { return v() + 10; } }",
				"public class Dog extends An { int v() { return 2; } }",
				"public class Us { public static int go() { An a = new Dog(); return a.base(); } }"));
		assertEquals(12, run(vm, "Us", "go"));
	}

	@Test
	public void exceptions() throws Exception {
		Vm vm = vm(compile(
				"public class Ex { public static int go() { try { div(); } "
						+ "catch (ArithmeticException e) { return 9; } finally { org.snapvm.InterpreterTest.Probe.marker = 5; } return 0; }"
						+ " static void div() { int x = 1/0; } }"));
		org.snapvm.InterpreterTest.Probe.marker = 0;
		assertEquals(9, run(vm, "Ex", "go"));
		assertEquals(5, org.snapvm.InterpreterTest.Probe.marker);
	}

	@Test
	public void arrays() throws Exception {
		Vm vm = vm(compile(
				"public class Ar { public static int go() { int[] a = new int[3];"
						+ " a[0]=1; a[1]=2; a[2]=3; String[] s = {\"a\",\"b\"}; return a[2]+s.length; } }"));
		assertEquals(5, run(vm, "Ar", "go"));
	}

	@Test
	public void longsAndDoubles() throws Exception {
		Vm vm = vm(compile(
				"public class Ld { public static long go() { long a = 3000000000L;"
						+ " double d = a / 2.5; return a + (long) d; } }"));
		assertEquals(3000000000L + 1200000000L, vm.invokeStatic("Ld", "go", "()J"));
	}

	/**
	 * lshl/lshr/lushr consumed [L,WIDE,int] (3 slots) but popped 2 — the
	 * result kept no WIDE slot and the next long op threw
	 * ClassCastException (or silently misread the stack). Found via
	 * 宠物王国6: CWA2DAPI.CWATools.getBytes does (J >> s) & 0xFF in a loop.
	 */
	@Test
	public void longShiftsKeepWide() throws Exception {
		long x = -1991225785L;
		long expected = ((x << 3) >>> 8) + (x >> 24) + (x >>> 4);
		Vm vm = vm(compile(
				"public class Lw { public static long go() {"
						+ " long x = -1991225785L;"
						+ " return ((x << 3) >>> 8) + (x >> 24) + (x >>> 4); } }"));
		assertEquals(expected, ((Long) vm.invokeStatic("Lw", "go", "()J")).longValue());
	}

	/**
	 * pop2 over TWO single-slot values must drop both. An obfuscated J2ME game
	 * (M3 burn-in) used `getstatic array; arraylength; pop2` inside an argument
	 * list — the residue shifted every following argument one slot, so the
	 * callee's Lbx; parameter received Integer 0 and the next invokevirtual hit
	 * "host class missing bx".
	 */
	@Test
	public void pop2TwoSingles() throws Exception {
		Map<String, byte[]> classes = compile(
				"public class P2 { static int id(int x) { return x; }"
						+ " public static int caller() { return id(77); } }");
		Vm vm = vm(classes);
		VmClass vc = vm.resolveClass("P2");
		ClassFile.Method m = vc.declaredMethods.get("caller|()I");
		// borrow the compiled `invokestatic id` cp index for our hand assembly
		byte[] orig = m.code.code;
		int at = -1;
		for (int i = 0; i + 2 < orig.length; i++)
			if ((orig[i] & 0xff) == 0xb8) { at = i; break; }
		assertTrue("fixture lacks invokestatic", at > 0);
		m.code.code = new byte[] {
				0x10, 4,                       // bipush 4 — value id() must see
				0x10, 60,                      // bipush 60 — junk single #1
				0x10, 70,                      // bipush 70 — junk single #2
				0x58,                          // pop2 — must drop BOTH junks
				(byte) 0xb8, orig[at + 1], orig[at + 2], // invokestatic id(I)I
				(byte) 0xac                    // ireturn
		};
		m.code.maxStack = 4;
		assertEquals(4, run(vm, "P2", "caller"));
	}

	/** pop2 on a wide pair (discarded long result) — the form that already worked. */
	@Test
	public void pop2WidePair() throws Exception {
		Vm vm = vm(compile(
				"public class PW { static long big() { return 123456789012L; }"
						+ " public static int go() { big(); return 7; } }"));
		assertEquals(7, run(vm, "PW", "go"));
	}

	/**
	 * dup2_x2 with two single-slot values over a wide pair: stack
	 * [wide,W,a,b] must become [a,b,wide,W,a,b]. The buggy code read the
	 * wide value from sp-5 (one slot below the pair) and wrote b at sp-5 —
	 * with exactly 4 stack slots that alone is index -1; deeper stacks
	 * silently scrambled the duplicated pair. Produces the value via
	 * iadd on the duplicated singles, and stores the long to prove it
	 * landed back intact and in position.
	 */
	@Test
	public void dup2x2TwoSinglesOverWide() throws Exception {
		Map<String, byte[]> classes = compile(
				"public class D2X2 { static long big() { return 0x11223344L; }"
						+ " public static int go() { return 0; } }");
		Vm vm = vm(classes);
		VmClass vc = vm.resolveClass("D2X2");
		ClassFile.Method m = vc.declaredMethods.get("go|()I");
		m.code.code = new byte[] {
				0x14, 0, 2,                    // ldc2_w #2 -> long constant (patched below)
				0x10, 11,                      // bipush 11  -> a
				0x10, 22,                      // bipush 22  -> b
				0x5e,                          // dup2_x2: [a,b,L,W,a,b]
				0x58,                          // pop2 -> [a,b,L,W]
				0x3f,                          // lstore_0 (locals 0,1 = L)
				0x60,                          // iadd -> a+b = 33
				(byte) 0xac                    // ireturn
		};
		m.code.maxStack = 8;
		m.code.maxLocals = 2;
		// patch ldc2_w cp index to the long constant the compiler emitted for big()
		int longCp = -1;
		for (int i = 1; i < 200; i++) {
			try {
				if (vc.cf.cpTag(i) == 5) { longCp = i; break; } // CONSTANT_Long
			} catch (Exception e) {
				break;
			}
		}
		assertTrue("no long cp entry", longCp > 0);
		m.code.code[1] = (byte) (longCp >> 8);
		m.code.code[2] = (byte) longCp;
assertEquals(33, run(vm, "D2X2", "go"));
	}

	/**
	 * Micro-benchmark, not a correctness test: tight int loop gives a rough
	 * instructions/sec figure for this host. Useful when judging whether a
	 * real game is compute-bound under the interpreter.
	 */
	@Test
	public void benchIntLoop() throws Exception {
		Vm vm = vm(compile(
				"public class B { public static int go() {"
						+ " int s = 0;"
						+ " for (int i = 0; i < 3000000; i++) { s += i; s ^= i; s += 7; }"
						+ " return s; } }"));
		long t0 = System.nanoTime();
		Object r = vm.invokeStatic("B", "go", "()I");
		long dt = System.nanoTime() - t0;
		System.out.println("benchIntLoop: " + r + " in " + dt / 1000000 + " ms"
				+ " (~" + (3_000_000L * 8 * 1000 / Math.max(1, dt / 1000000)) + " ops/s est)");
	}

	/**
	 * A host->guest callback arriving while executorThread still names this
	 * host thread but executor is null must NOT NPE on cur.frames. On device,
	 * a VmError escaping pump() left exactly that poisoned pair behind; every
	 * later keyPressed/keyReleased/paint callback then NPE'd inside
	 * callFromHost and was swallowed by the caller — which played on hardware
	 * as stuck keys, latched movement and huge input latency.
	 */
	@Test
	public void callFromHostWithStaleExecutorThread() throws Exception {
		Vm vm = vm(compile(
				"public class Cb { int last; public void key(int k) { last = k; }"
						+ " public int get() { return last; } }"));
		VmObject recv = vm.newGuestObject("Cb");
		java.lang.reflect.Field ft = Vm.class.getDeclaredField("executorThread");
		java.lang.reflect.Field fe = Vm.class.getDeclaredField("executor");
		ft.setAccessible(true);
		fe.setAccessible(true);
		ft.set(vm, Thread.currentThread());
		fe.set(vm, null);
		try {
			vm.callFromHost(recv, "key", "(I)V", 7);
		} finally {
			ft.set(vm, null);
		}
		assertEquals(7, vm.callFromHost(recv, "get", "()I"));
	}

	@Test
	public void greenThreads() throws Exception {
		Vm vm = vm(compile(
				"public class Gt { static int val; public static int go() {"
						+ " Thread th = new Thread(new Runnable() { public void run() { val = 42; } });"
						+ " th.start(); try { th.join(); } catch (InterruptedException e) {} return val; } }"));
		assertEquals(42, run(vm, "Gt", "go"));
	}

	/**
	 * A guest thread busy-spinning without ever blocking must not starve
	 * threads scheduled after it. Silent Hill 3's render loop spins on
	 * Canvas.isShown() with no sleep; first-runnable-wins scheduling then
	 * starved the main-loop thread created later and the screen stayed
	 * black. Real J2ME threads are preemptive — the green scheduler must
	 * round-robin.
	 */
	@Test
	public void roundRobinScheduling() throws Exception {
		Vm vm = vm(compile(
				"public class Rr { static int flag;"
						+ " public static void spin() { for (;;) {} }"
						+ " public static void fin() { flag = 42; }"
						+ " public static int get() { return flag; }"
						+ " public static int go() {"
						+ "  new Thread(new Runnable() { public void run() { spin(); } }).start();"
						+ "  new Thread(new Runnable() { public void run() { fin(); } }).start();"
						+ "  return 0; } }"));
		run(vm, "Rr", "go");
		for (int i = 0; i < 8; i++)
			vm.pump(50_000);
		assertEquals(42, vm.invokeStatic("Rr", "get", "()I"));
	}

	/** Host fixture: wrapper-typed members for fromHost reference-slot tests. */
	public static class BoxHolder {
		public static Short shortField = Short.valueOf((short) 9);
		public static boolean primBool = true;
		public static Short box(short v) {
			return v;
		}
	}

	/**
	 * Silent Hill 3 froze on its Playsoft splash: PointFont.DrawChar stores
	 * Short.valueOf() results in a Hashtable and later does checkcast
	 * java/lang/Short — but fromHost normalized every host wrapper to
	 * Integer, so the checkcast always threw and killed the main-loop
	 * thread. Reference-typed slots must keep the real wrapper object;
	 * primitive slots still normalize to Integer.
	 */
	@Test
	public void boxedWrappersKeepIdentity() throws Exception {
		Vm vm = vm(compile(
				"public class Bx {"
						+ " public static int roundTrip() {"
						+ "  java.util.Hashtable h = new java.util.Hashtable();"
						+ "  h.put(\"k\", Short.valueOf((short) 7));"
						+ "  Object o = h.get(\"k\");"
						+ "  if (!(o instanceof Short)) return -1;"
						+ "  return ((Short) o).shortValue(); }"
						+ " public static int takeShort(Short s) { return s.shortValue(); }"
						+ " public static int fieldRead() {"
						+ "  return org.snapvm.InterpreterTest.BoxHolder.shortField.shortValue(); }"
						+ " public static int methodRead() {"
						+ "  return org.snapvm.InterpreterTest.BoxHolder.box((short) 11).shortValue(); }"
						+ " public static int primRead() {"
						+ "  return org.snapvm.InterpreterTest.BoxHolder.primBool ? 3 : 0; } }"));
		assertEquals(7, run(vm, "Bx", "roundTrip"));
		assertEquals(9, run(vm, "Bx", "fieldRead"));
		assertEquals(11, run(vm, "Bx", "methodRead"));
		assertEquals(3, run(vm, "Bx", "primRead"));
		// 'L' parameter slot must receive the real Short, not an Integer
		assertEquals(5, vm.invokeStatic("Bx", "takeShort",
				"(Ljava/lang/Short;)I", Short.valueOf((short) 5)));
	}

	@Test
	public void clinit() throws Exception {
		Vm vm = vm(compile(
				"public class Ci { static int v = init(); static int init() { return 11; }"
						+ " public static int go() { return v + 1; } }"));
		assertEquals(12, run(vm, "Ci", "go"));
	}

	/**
	 * The M3 boundary spike: a guest midlet extending a HOST abstract class,
	 * driven by host-side lifecycle calls, with guest->host API calls and a
	 * host-interface callback (CommandListener) fired back into the guest.
	 */
	@Test
	public void midletLifecycle() throws Exception {
		javax.microedition.midlet.MIDlet.events.clear();
		Vm vm = vm(compile(
				"public class GameMidlet extends javax.microedition.midlet.MIDlet"
						+ " implements javax.microedition.lcdui.CommandListener {"
						+ " public static int flag;"
						+ " public GameMidlet() { flag |= 16; }"
						+ " protected void startApp() {"
						+ "  javax.microedition.lcdui.Display d = javax.microedition.lcdui.Display.getDisplay(this);"
						+ "  javax.microedition.lcdui.Form f = new javax.microedition.lcdui.Form(\"hi\");"
						+ "  f.addCommand(new javax.microedition.lcdui.Command(\"exit\", 7, 0));"
						+ "  f.setCommandListener(this);"
						+ "  d.setCurrent(f);"
						+ "  flag |= 1; }"
						+ " protected void pauseApp() { flag |= 2; }"
						+ " protected void destroyApp(boolean u) { flag |= 4; }"
						+ " public void commandAction(javax.microedition.lcdui.Command c,"
						+ "    javax.microedition.lcdui.Displayable d) {"
						+ "  if (c.getLabel().equals(\"exit\")) { flag |= 8; notifyDestroyed(); } }"
						+ " public static int probe() { return flag; } }"));
		vm.registerShadow(javax.microedition.midlet.MIDlet.class,
				new Vm.ShadowFactory() {
					public Object create(Vm vm, VmObject guest) {
						return new MidletShadow(vm, guest);
					}
				});
		VmObject guest = vm.newGuestObject("GameMidlet");
		// the host-super <init> manifested the shadow midlet
		assertTrue(guest.hostShadow instanceof javax.microedition.midlet.MIDlet);
		MidletShadow midlet = (MidletShadow) guest.hostShadow;
		assertEquals(16, vm.invokeStatic("GameMidlet", "probe", "()I"));

		midlet.launch(); // AMS-style: host calls into guest code
		assertEquals(16 | 1, vm.invokeStatic("GameMidlet", "probe", "()I"));
		// guest passed `this` to Display.getDisplay -> host saw the same shadow
		javax.microedition.lcdui.Display d =
				javax.microedition.lcdui.Display.getDisplay(midlet);
		assertSame(midlet, d.owner);
		javax.microedition.lcdui.Form f =
				(javax.microedition.lcdui.Form) d.getCurrent();
		assertEquals("hi", f.getTitle());
		// host fires the command -> CommandListener proxy -> guest commandAction
		f.fire(f.commands.get(0));
		assertEquals(16 | 1 | 8, vm.invokeStatic("GameMidlet", "probe", "()I"));
		// guest notifyDestroyed() reached the host through the shadow
		assertEquals(java.util.Arrays.asList("destroyed"),
				javax.microedition.midlet.MIDlet.events);

		midlet.pause();
		midlet.destroy(true);
		assertEquals(16 | 1 | 8 | 2 | 4, vm.invokeStatic("GameMidlet", "probe", "()I"));
	}

	/** Shadow for guest-extends-TimerTask: a real host TimerTask forwarding run(). */
	public static class TimerShadow extends java.util.TimerTask {
		private final Vm vm;
		private final VmObject guest;

		TimerShadow(Vm vm, VmObject guest) {
			this.vm = vm;
			this.guest = guest;
		}

		public void run() {
			vm.callFromHost(guest, "run", "()V");
		}
	}

	/** Guest extends java.util.TimerTask: shadow created at super-<init>,
	 * guest object marshals to the shadow for Timer.schedule, the host Timer's
	 * own thread calls run() back into the guest, cancel() reaches the shadow. */
	@Test
	public void timerTaskShadow() throws Exception {
		Vm vm = vm(compile(
				"public class Tt {"
						+ " public static int fired;"
						+ " public static class Task extends java.util.TimerTask {"
						+ "  public void run() { fired = 1; }"
						+ " }"
						+ " public static int go() {"
						+ "  Task t = new Task();"
						+ "  java.util.Timer timer = new java.util.Timer();"
						+ "  timer.schedule(t, 10);"
						+ "  Task dead = new Task();"
						+ "  dead.cancel();"
						+ "  try { Thread.sleep(300); } catch (InterruptedException e) {}"
						+ "  timer.cancel();"
						+ "  return fired;"
						+ " } }"));
		vm.registerShadow(java.util.TimerTask.class, new Vm.ShadowFactory() {
			public Object create(Vm vm, VmObject guest) {
				return new TimerShadow(vm, guest);
			}
		});
		assertEquals(1, run(vm, "Tt", "go"));
	}

	/** Two green threads: wait() blocks, releases the monitor, notify() wakes it. */
	@Test
	public void waitNotify() throws Exception {
		Vm vm = vm(compile(
				"public class Wn {"
						+ " static final Object LOCK = new Object();"
						+ " static int stage;"
						+ " public static int go() throws Exception {"
						+ "  stage = 0;"
						+ "  Thread t = new Thread(new Runnable() { public void run() {"
						+ "   synchronized (LOCK) {"
						+ "    stage = 1;"
						+ "    try { LOCK.wait(); } catch (InterruptedException e) {}"
						+ "    stage = 2;"
						+ "   }"
						+ "  }});"
						+ "  t.start();"
						+ "  while (stage < 1) Thread.sleep(1);"
						+ "  synchronized (LOCK) { LOCK.notify(); }"
						+ "  t.join();"
						+ "  return stage;"
						+ " } }"));
		assertEquals(2, run(vm, "Wn", "go"));
	}

	/** synchronized blocks: mutual exclusion across a sleep, reentrancy, and
	 * IllegalMonitorStateException on wait() without holding the monitor. */
	@Test
	public void monitorContention() throws Exception {
		Vm vm = vm(compile(
				"public class Mc {"
						+ " static final Object LOCK = new Object();"
						+ " static int inside;"
						+ " public static int go() throws Exception {"
						+ "  inside = 0;"
						+ "  Thread t = new Thread(new Runnable() { public void run() {"
						+ "   synchronized (LOCK) {"
						+ "    synchronized (LOCK) { }"
						+ "    inside++;"
						+ "   }"
						+ "  }});"
						+ "  t.start();"
						+ "  synchronized (LOCK) {"
						+ "   Thread.sleep(15);"
						+ "   if (inside != 0) return -1;" // t must stay out while main sleeps holding LOCK
						+ "  }"
						+ "  t.join();"
						+ "  int imse = 0;"
						+ "  try { new Object().wait(); }"
						+ "  catch (IllegalMonitorStateException e) { imse = 1; }"
						+ "  synchronized (LOCK) { inside += 10; }" // t must have fully released
						+ "  return inside * 10 + imse;" // 11*10+1
						+ " } }"));
		assertEquals(111, run(vm, "Mc", "go"));
	}

	/** ACC_SYNCHRONIZED guest methods take the receiver's monitor. */
	@Test
	public void synchronizedMethod() throws Exception {
		Vm vm = vm(compile(
				"public class Sm {"
						+ " static int inside;"
						+ " synchronized void bump() {"
						+ "  try { Thread.sleep(10); } catch (InterruptedException e) {}"
						+ "  inside++;"
						+ " }"
						+ " public static int go() throws Exception {"
						+ "  inside = 0;"
						+ "  final Sm sm = new Sm();"
						+ "  Thread t = new Thread(new Runnable() { public void run() {"
						+ "   synchronized (sm) {"
						+ "    try { Thread.sleep(15); } catch (InterruptedException e) {}"
						+ "    inside += 1000;"
						+ "   }"
						+ "  }});"
						+ "  t.start();"
						+ "  Thread.sleep(1);" // let t grab sm's monitor first
						+ "  sm.bump();"       // must block until t's sync block exits
						+ "  t.join();"
						+ "  return inside;"   // 1000 + 1
						+ " } }"));
assertEquals(1001, run(vm, "Sm", "go"));
	}

	/** A guest class extending host Thread: start() launches run() as a green thread. */
	@Test
	public void guestExtendsThread() throws Exception {
		Vm vm = vm(compile(
				"public class Ts extends Thread {"
						+ " static int ran;"
						+ " public void run() { ran = 5; }"
						+ " public static int go() throws Exception {"
						+ "  ran = 0;"
						+ "  Ts t = new Ts();"
						+ "  t.start();"
						+ "  t.join();"
						+ "  return ran;"
						+ " } }"));
		assertEquals(5, run(vm, "Ts", "go"));
	}

	/** Multi-dim arrays keep real runtime types; guest arrays track component type. */
	@Test
	public void multiArrays() throws Exception {
		Vm vm = vm(compile(
				"public class Ma {"
						+ " public static int go() {"
						+ "  int[][] a = new int[2][3];"
						+ "  a[1][2] = 9;"
						+ "  int r = a[1][2] + a.length;"              // 11
						+ "  String[][] s = new String[2][2];"
						+ "  s[0][1] = \"x\";"
						+ "  Object[] o = s;"
						+ "  r += ((String[][]) o)[0][1].length();"    // +1 = 12
						+ "  Ma[] g = new Ma[2];"
						+ "  Object o2 = g;"
						+ "  r += ((Ma[]) o2).length;"                 // +2 = 14
						+ "  int ase = 0;"
						+ "  try { Object[] bad = new Ma[1]; bad[0] = \"nope\"; }"
						+ "  catch (ArrayStoreException e) { ase = 1; }"
						+ "  ase <<= 1;"
						+ "  try { Object[] ok = new Object[1]; ok[0] = \"fine\"; }"
						+ "  catch (ArrayStoreException e) { ase |= 1; }" // must NOT throw
						+ "  return r * 100 + ase;"                    // 1400 + 2
						+ " } }"));
		assertEquals(1402, run(vm, "Ma", "go"));
	}

	/** instanceof/checkcast matrix: interfaces, arrays, guest types, null. */
	@Test
	public void instanceofMatrix() throws Exception {
		Vm vm = vm(compile(
				"public class Im {"
						+ " static class Sub extends Im {}"
						+ " public static int go() {"
						+ "  int r = 0;"
						+ "  Object o = \"x\";"
						+ "  if (o instanceof String) r |= 1;"
						+ "  if (o instanceof CharSequence) r |= 2;"
						+ "  if (o instanceof Integer) return -1;"
						+ "  String[] sa = new String[2];"
						+ "  if (sa instanceof Object[]) r |= 4;"
						+ "  if (sa instanceof Object) r |= 8;"
						+ "  if (sa instanceof Cloneable) r |= 16;"
						+ "  int[] ia = new int[3];"
						+ "  if (!(ia instanceof int[])) return -2;"
						+ "  Im[] ga = new Im[1];"
						+ "  if (ga instanceof Im[]) r |= 32;"
						+ "  if (ga instanceof Object[]) r |= 64;"
						+ "  Object ga2 = ga;"
						+ "  if (ga2 instanceof String[]) return -3;"
						+ "  String nul = null;"
						+ "  if (nul instanceof String) return -4;"
						+ "  Im i = new Sub();"
						+ "  if (i instanceof Im) r |= 128;"
						+ "  if (i instanceof Sub) r |= 256;"
						+ "  Object sa2 = sa;"
						+ "  if (sa2 instanceof Im[]) return -5;"
						+ "  return r;" // 1+2+4+8+16+32+64+128+256
						+ " } }"));
		assertEquals(511, run(vm, "Im", "go"));
	}

	/** Guest interface dispatch through invokeinterface, incl. inherited impl. */
	@Test
	public void interfaces() throws Exception {
		Vm vm = vm(compile(
				"interface Ifc { int m(); }",
				"interface SubIfc extends Ifc { int n(); }",
				"public class Impl implements SubIfc { public int m() { return 5; } public int n() { return m() * 2; } }",
				"public class Base implements Ifc { public int m() { return 7; } }",
				"public class Kid extends Base { }",
				"public class Iu { public static int go() {"
						+ "  Ifc a = new Impl();"
						+ "  SubIfc b = new Impl();"
						+ "  Ifc c = new Kid();"   // interface meth inherited from guest super
						+ "  return a.m() * 100 + b.n() * 10 + c.m();"
						+ " } }")); // 500 + 100 + 7
		assertEquals(607, run(vm, "Iu", "go"));
	}

	/** Class.newInstance on a guest class runs its <init>. */
	@Test
	public void classNewInstance() throws Exception {
		Vm vm = vm(compile(
				"public class Ni {"
						+ " int v = 7;"
						+ " public static int go() throws Exception {"
						+ "  Object o = Ni.class.newInstance();"
						+ "  Object o2 = Class.forName(\"Ni\").newInstance();"
						+ "  return ((Ni) o).v * 10 + ((Ni) o2).v;"
						+ " } }")); // 77
		assertEquals(77, run(vm, "Ni", "go"));
	}

	/** Guest exceptions keep message/cause across athrow; Object methods dispatch
	 * even when the host boundary isn't java.lang.Object. */
	@Test
	public void athrowChain() throws Exception {
		Vm vm = vm(compile(
				"public class MyEx extends Exception {"
						+ " public MyEx(String m) { super(m); }"
						+ " public MyEx(String m, Throwable c) { super(m, c); }"
						+ "}",
				"public class Ax {"
						+ " public static int go() {"
						+ "  int r = 0;"
						+ "  try { throw new MyEx(\"boom\"); }"
						+ "  catch (MyEx e) {"
						+ "   if (\"boom\".equals(e.getMessage())) r |= 1;"
						+ "   if (e.getCause() == null) r |= 2;"
						+ "   String s = e.toString();"
						+ "   if (s != null && s.indexOf(\"MyEx\") >= 0) r |= 4;"
						+ "   e.printStackTrace();"
						+ "   if (e.equals(e)) r |= 64;"
						+ "   if (e.hashCode() != 0 || e.hashCode() == 0) r |= 128;"
						+ "  }"
						+ "  try { throw new MyEx(\"wrap\", new IllegalStateException(\"inner\")); }"
						+ "  catch (MyEx e) {"
						+ "   if (\"inner\".equals(e.getCause().getMessage())) r |= 8;"
						+ "   if (\"wrap\".equals(e.getMessage())) r |= 16;"
						+ "  }"
						+ "  try { throw new IllegalArgumentException(\"host-ex\"); }"
						+ "  catch (RuntimeException e) {"
						+ "   if (\"host-ex\".equals(e.getMessage())) r |= 32;"
						+ "  }"
						+ "  return r;"
						+ " } }"));
		assertEquals(255, run(vm, "Ax", "go"));
	}

	/** High-frequency String calls go through host reflection. */
	@Test
	public void stringApis() throws Exception {
		Vm vm = vm(compile(
				"public class Sa { public static int go() {"
						+ "  String s = \"hello world\";"
						+ "  return s.indexOf(\"world\")"
						+ "    + s.substring(0, 5).length()"
						+ "    + (s.charAt(0) - 'h')"
						+ "    + (\"abc\".compareTo(\"abd\") < 0 ? 1 : 0)"
						+ "    + (s.startsWith(\"hello\") ? 1 : 0)"
						+ "    + String.valueOf(42).length()"
						+ "    + \"  pad  \".trim().length();"
						+ " } }")); // 6+5+0+1+1+2+3
		assertEquals(18, run(vm, "Sa", "go"));
	}

	/** Sparse lookupswitch and dense tableswitch. */
	@Test
	public void switches() throws Exception {
		Vm vm = vm(compile(
				"public class Sw {"
						+ " public static int go(int n) {"
						+ "  switch (n) { case 1: return 10; case 1000: return 20;"
						+ "    case -77: return 30; default: return n; } }"
						+ " public static int dense(int n) {"
						+ "  switch (n) { case 0: return 100; case 1: return 200;"
						+ "    case 2: return 300; case 3: return 400; default: return -1; } }"
						+ " }"));
		assertEquals(10, vm.invokeStatic("Sw", "go", "(I)I", 1));
		assertEquals(20, vm.invokeStatic("Sw", "go", "(I)I", 1000));
		assertEquals(30, vm.invokeStatic("Sw", "go", "(I)I", -77));
		assertEquals(55, vm.invokeStatic("Sw", "go", "(I)I", 55));
		assertEquals(300, vm.invokeStatic("Sw", "dense", "(I)I", 2));
		assertEquals(-1, vm.invokeStatic("Sw", "dense", "(I)I", 9));
	}

	/** Float arithmetic plus NaN comparison directions (fcmpl/fcmpg/dcmpl/dcmpg). */
	@Test
	public void floatsNaN() throws Exception {
		Vm vm = vm(compile(
				"public class Fn { public static int go() {"
						+ "  float a = 1.5f * 2f;"
						+ "  float b = 7f / 2f;"
						+ "  int r = (int) (a + b);"        // 6
						+ "  float z = 0f;"
						+ "  float nf = z / z;"            // runtime NaN
						+ "  if (nf == nf) return -1;"
						+ "  if (nf > 1.0f) return -2;"
						+ "  if (nf < -1.0f) return -3;"
						+ "  double dz = 0.0;"
						+ "  double dn = dz / dz;"
						+ "  if (dn == dn) return -4;"
						+ "  if (dn >= 0 || dn < 0) return -5;"
						+ "  return r;"
						+ " } }"));
		assertEquals(6, run(vm, "Fn", "go"));
	}

	/** finally runs on both normal-return and exception paths. */
	@Test
	public void finallyReturn() throws Exception {
		Vm vm = vm(compile(
				"public class Fr {"
						+ " static int side;"
						+ " static int f() {"
						+ "  try { if (System.currentTimeMillis() > 0) return 7; }"
						+ "  finally { side = 3; }"
						+ "  return 0; }"
						+ " static int g() {"
						+ "  try { int x = 1 / 0; return x; }"
						+ "  catch (ArithmeticException e) { return 5; }"
						+ "  finally { side += 30; } }"
						+ " public static int go() {"
						+ "  side = 0;"
						+ "  int a = f();"
						+ "  int b = g();"
						+ "  return a * 100 + b * 10 + side;" // 700+50+33
						+ " } }"));
		assertEquals(783, run(vm, "Fr", "go"));
	}

	@Test
	public void stringIdentity() throws Exception {
		Vm vm = vm(compile(
				"public class Si { static final String A = \"lit\"; public static int go() {"
						+ " return (A == \"lit\") ? 1 : 0; } }"));
		assertEquals(1, run(vm, "Si", "go"));
	}

	// Obfuscators hide super fields with same name+desc. The flattened
	// fieldSlots map keeps only the child's index per key, so defaults built
	// from that map left the hidden slot null — getfield of the never-written
	// super int returned null and the first iadd unboxed it (host NPE inside
	// step → guest NPE at the read site; 劲舞花儿朵朵 actors died every frame,
	// stage rendered with no characters). The child's own field must still
	// resolve to its own slot.
	@Test
	public void hiddenSuperFieldKeepsJvmDefault() throws Exception {
		Vm vm = vm(compile(
				"public class Sup {"
						+ " private int a;"
						+ " private int[] arr = new int[3];"
						+ " public int go() { arr[2] += 4; a += arr[2]; return a; } }",
				"public class Sub extends Sup {"
						+ " public int a = 99;"
						+ " public int[] arr = new int[5]; }",
				"public class Hf { public static int go() {"
						+ " Sub s = new Sub();"
						+ " return s.go() * 100 + s.a; } }"));
		assertEquals(499, run(vm, "Hf", "go"));
	}

	// Sup has a <clinit>, Sub has none. Constructing Sub must run Sup's
	// <clinit> under Sup's own constant pool — not borrowed onto Sub.
	@Test
	public void superClinitOwnConstantPool() throws Exception {
		Vm vm = vm(compile(
				"public class Sup { public static int s; static { s = 41; } public int v = 1; }",
				"public class Sub extends Sup { public int w = 2; }",
				"public class Sc { public static int go() {"
						+ " return new Sub().w + Sup.s + new Sub().v; } }"));
		assertEquals(44, run(vm, "Sc", "go"));
	}

	// bastore/castore/sastore take an int on the operand stack and truncate it;
	// java.lang.reflect.Array.set refuses Integer for byte/char/short/boolean
	// arrays, so the interpreter must narrow explicitly.
	@Test
	public void primitiveArrayNarrowingStores() throws Exception {
		Vm vm = vm(compile(
				"public class PA { public static int go() {"
						+ " byte[] b = new byte[2]; b[0] = 22; b[1] = -1;"
						+ " short[] s = new short[1]; s[0] = 300;"
						+ " char[] c = new char[1]; c[0] = 65;"
						+ " boolean[] z = new boolean[2]; z[0] = true;"
						+ " return b[0] * 10000 + b[1] + s[0] * 10 + c[0]"
						+ " + (z[0] ? 1 : 0); } }"));
		assertEquals(220000 - 1 + 3000 + 65 + 1, run(vm, "PA", "go"));
	}

	@Test
	public void objectArrayArgDescriptor() throws Exception {
		Vm vm = vm(compile(
				"public class AA { public static int go() {"
						+ " String[] xs = {\"ab\", \"c\", \"def\"};"
						+ " return take(xs, 7); }"
						+ " static int take(String[] xs, int n) {"
						+ " return xs[0].length() * 100 + xs[2].length() * 10 + n; } }"));
		assertEquals(237, run(vm, "AA", "go"));
	}

	// J2ME games use Runtime.getRuntime().getClass().getResourceAsStream() as a
	// generic jar-resource lookup. The receiver is a HOST Class object, so it
	// must still reach the guest class sources — not the host classloader.
	// Also covers relative-path normalization on a guest Class and null for
	// missing resources. `supports.*` props ride j2meProps like midlet props do.
	@Test
	public void hostClassResourceAsStream() throws Exception {
		Map<String, byte[]> classes = compile(
				"public class Rs {"
						+ " public static int go() throws Exception {"
						+ "  int r = 0;"
						+ "  java.io.InputStream a = Runtime.getRuntime().getClass()"
						+ "   .getResourceAsStream(\"/testres.bin\");"
						+ "  if (a == null) return -1;"
						+ "  r = a.read();"                                 // 42
						+ "  a.close();"
						+ "  java.io.InputStream b = Rs.class.getResourceAsStream(\"rel.bin\");"
						+ "  if (b == null) return -2;"
						+ "  r += b.read() * 100;"                          // +700
						+ "  b.close();"
						+ "  if (Rs.class.getResourceAsStream(\"/missing.bin\") != null) return -3;"
						+ "  if (Runtime.getRuntime().getClass()"
						+ "   .getResourceAsStream(\"/missing.bin\") != null) return -4;"
						+ "  if (!\"yes\".equals(System.getProperty(\"supports.mixing\"))) return -5;"
						+ "  return r;"                                     // 742
						+ " } }");
		Vm vm = new Vm();
		vm.setJ2meProperty("supports.mixing", "yes");
		vm.addClassSource(new Vm.ClassSource() {
			public byte[] findClass(String name) {
				return classes.get(name.replace('/', '.'));
			}

			public InputStream findResource(String path) {
				if (path.equals("testres.bin"))
					return new java.io.ByteArrayInputStream(new byte[]{42});
				if (path.equals("rel.bin"))
					return new java.io.ByteArrayInputStream(new byte[]{7});
				return null;
			}
		});
		assertEquals(742, run(vm, "Rs", "go"));
	}

	/**
	 * J2ME games assume jar resources fill a buffer in one read() — real
	 * devices did that, and JL-Modern's dex path returns ByteArrayInputStream.
	 * Android's ZipFile inflater short-reads DEFLATED entries (Silent Hill 3:
	 * 468/471 on _bb → signature check at l._q@141 fails → notifyDestroyed).
	 * getResource must buffer; a hostile 1-byte-per-read source proves it.
	 */
	@Test
	public void jarResourceReadFills() throws Exception {
		Map<String, byte[]> classes = compile(
				"public class Jr { public static int go() throws Exception {"
						+ "  java.io.InputStream in = Jr.class.getResourceAsStream(\"/blob.bin\");"
						+ "  if (in == null) return -1;"
						+ "  byte[] buf = new byte[471];"
						+ "  int n = in.read(buf);"
						+ "  in.close();"
						+ "  return n + buf[470];"
						+ " } }");
		final byte[] blob = new byte[471];
		blob[470] = 7; // tail byte — only seen if the read actually filled
		Vm vm = new Vm();
		vm.addClassSource(new Vm.ClassSource() {
			public byte[] findClass(String name) {
				return classes.get(name.replace('/', '.'));
			}

			public InputStream findResource(String path) {
				if (!path.equals("blob.bin"))
					return null;
				return new InputStream() {
					int pos;
					public int read() {
						return pos < blob.length ? blob[pos++] & 0xff : -1;
					}
					public int read(byte[] b, int off, int len) {
						if (pos >= blob.length)
							return -1;
						b[off] = blob[pos++];
						return 1;
					}
				};
			}
		});
		assertEquals(478, vm.invokeStatic("Jr", "go", "()I"));
	}

	/**
	 * The JDK fast-paths (Vector/StringBuffer/String/Math/IO) must behave
	 * identically to the reflective route — same results, same exceptions.
	 */
	@Test
	public void fastBuiltins() throws Exception {
		Vm vm = vm(compile(
				"import java.util.Vector;\n"
						+ "import java.util.Hashtable;\n"
						+ "import java.util.Enumeration;\n"
						+ "public class Fb { static class Greet {"
						+ "  public String toString() { return \"hi-42\"; } }"
						+ "public static int go() {"
						+ "  Vector v = new Vector();"
						+ "  for (int i = 0; i < 5; i++) v.addElement(\"s\" + i);"
						+ "  int sum = 0;"
						+ "  for (int i = 0; i < v.size(); i++)"
						+ "    sum += ((String) v.elementAt(i)).length();"   // +10
						+ "  StringBuffer sb = new StringBuffer();"
						+ "  sb.append(\"ab\").append(12).append('c').append(true);"
						+ "  String s = sb.toString();"                    // "ab12ctrue"
						+ "  sum += s.length() + s.indexOf(\"12\") + s.substring(2).length();" // +9+2+7
						+ "  Enumeration e = v.elements(); int n = 0;"
						+ "  while (e.hasMoreElements()) { e.nextElement(); n++; }" // n=5
						+ "  Hashtable h = new Hashtable(); h.put(\"k\", Integer.valueOf(7));"
						+ "  sum += n + h.size() + ((Integer) h.get(\"k\")).intValue();" // +13
						+ "  sum += Math.max(3, 9) + Math.min(4, 2);"       // +11
						+ "  sum += Integer.parseInt(\"31\");"              // +31
						+ "  byte[] raw = {0,0,0,5};"
						+ "  java.io.DataInputStream dis = new java.io.DataInputStream("
						+ "      new java.io.ByteArrayInputStream(raw));"
						+ "  try { sum += dis.readInt(); dis.close(); }"
						+ "  catch (java.io.IOException ex) { return -99; }" // +5
						+ "  v.removeElementAt(0);"
						+ "  sum += v.size() * 100;"                        // +400
						// guest objects marshaled into host String APIs must
						// run their own toString — "hi-42".length() = 5
						+ "  sum += String.valueOf(new Greet()).length()"   // +5
						+ "       + new String(new StringBuffer(\"xy\")).length();" // +2
						+ "  return sum;"                                   // 495
						+ " } }"));
		assertEquals(495, run(vm, "Fb", "go"));
	}

	@Test
	public void stringMarshalGuest() throws Exception {
		Vm vm = vm(compile(
				"public class Sg { static class G { public String toString() { return \"hi-42\"; } }"
						+ "public static int a() { return String.valueOf(new G()).length(); }"
						+ "public static int b() { return new String(new StringBuffer(\"xy\")).length(); }"
						+ "public static int c() { return String.valueOf(new G()).length()"
						+ "    + new String(new StringBuffer(\"xy\")).length(); }"
						+ " }"));
		assertEquals(5, run(vm, "Sg", "a"));
		assertEquals(2, run(vm, "Sg", "b"));
		assertEquals(7, run(vm, "Sg", "c"));
	}

	/**
	 * Guest CLDC Vector leaf ops are registered as fast calls — they must
	 * behave identically to the interpreted bodies: grow policy, shifting,
	 * tail nulling, and the exact exception types a guest catch sees.
	 */
	@Test
	public void vectorFastCalls() throws Exception {
		Vm vm = vm(compile(
				"import java.util.Vector;\n"
						+ "public class Vf { public static int go() {"
						+ "  Vector v = new Vector();"
						+ "  for (int i = 0; i < 300; i++) v.addElement(Integer.valueOf(i));"
						+ "  int sum = v.size();"                             // +300
						+ "  sum += ((Integer) v.elementAt(299)).intValue();"   // +299
						+ "  v.setElementAt(Integer.valueOf(7), 0);"
						+ "  sum += ((Integer) v.firstElement()).intValue();"   // +7
						+ "  sum += ((Integer) v.lastElement()).intValue();"    // +299
						+ "  sum += ((Integer) v.get(1)).intValue();"           // +1
						+ "  v.insertElementAt(Integer.valueOf(5), 0);"
						+ "  sum += ((Integer) v.elementAt(0)).intValue() + v.size();" // +5+301
						+ "  v.removeElementAt(0);"
						+ "  sum += v.size();"                                  // +300
						+ "  sum += v.capacity() >= 300 ? 1 : 0;"               // +1
						+ "  v.setSize(5);"
						+ "  sum += v.size() + ((Integer) v.elementAt(4)).intValue();" // +5+4
						+ "  sum += v.toArray().length;"                        // +5
						+ "  v.removeAllElements();"
						+ "  sum += v.isEmpty() ? 10 : 0;"                      // +10
						+ "  sum += v.size();"                                  // +0
						+ "  int caught = 0;"
						+ "  try { v.elementAt(0); }"
						+ "  catch (ArrayIndexOutOfBoundsException e) { caught = 40; }"
						+ "  sum += caught;"                                    // +40
						+ "  try { v.firstElement(); }"
						+ "  catch (java.util.NoSuchElementException e) { caught = 50; }"
						+ "  sum += caught;"                                    // +50
						+ "  v.addElement(Integer.valueOf(9)); v.setSize(8);"
						+ "  sum += v.elementAt(7) == null ? 60 : 0;"           // +60
						+ "  sum += ((Integer) v.elementAt(0)).intValue();"     // +9
						+ "  return sum;"                                       // 1696
						+ " } }"));
		assertEquals(1696, run(vm, "Vf", "go"));
	}

	/**
	 * A guest subclass overriding a fast-called method resolves to its own
	 * declaring class — the fast call must NOT fire; the override body runs.
	 */
	@Test
	public void vectorFastCallOverride() throws Exception {
		Vm vm = vm(compile(
				"public class Vo { static class V2 extends java.util.Vector {"
						+ "  int hits;"
						+ "  public synchronized Object elementAt(int i) {"
						+ "    hits++; return super.elementAt(i); } }"
						+ "public static int go() {"
						+ "  V2 v = new V2();"
						+ "  v.addElement(\"x\");"
						+ "  Object r = v.elementAt(0);"
						+ "  return v.hits * 10 + ((String) r).length();"      // 11
						+ " } }"));
		assertEquals(11, run(vm, "Vo", "go"));
	}

	/**
	 * The dispatch itself: a fast call registered on a plain guest method is
	 * invoked with raw guest args (receiver at [0]) and its return is pushed
	 * onto the caller's operand stack.
	 */
	@Test
	public void guestFastCallDispatch() throws Exception {
		Vm vm = vm(compile(
				"public class Gd { public int bump(int x) { return -1; }"
						+ " public static int go() { return new Gd().bump(41); } }"));
		final int[] hit = {0};
		vm.registerFastCall("Gd.bump(I)I", a -> {
			hit[0]++;
			return ((Integer) a[1]) + 1;
		});
		assertEquals(42, run(vm, "Gd", "go"));
		assertEquals(1, hit[0]);
	}

	/** Host-call dispatch throughput: Vector.size() in a guest loop. */
	@Test
	public void benchHostCall() throws Exception {
		Vm vm = vm(compile(
				"public class Bc { public static int go() {"
						+ "  java.util.Vector v = new java.util.Vector();"
						+ "  int s = 0;"
						+ "  for (int i = 0; i < 100000; i++) s += v.size();"
						+ "  return s; } }"));
		long t0 = System.nanoTime();
		assertEquals(0, run(vm, "Bc", "go"));
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("benchHostCall: 100000 calls in " + ms + " ms (~"
				+ (100000000 / Math.max(1, ms)) + " calls/s)");
	}
}
