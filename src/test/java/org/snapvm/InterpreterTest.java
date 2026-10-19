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

	@Test
	public void greenThreads() throws Exception {
		Vm vm = vm(compile(
				"public class Gt { static int val; public static int go() {"
						+ " Thread th = new Thread(new Runnable() { public void run() { val = 42; } });"
						+ " th.start(); try { th.join(); } catch (InterruptedException e) {} return val; } }"));
		assertEquals(42, run(vm, "Gt", "go"));
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
}
