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

	@Test
	public void stringIdentity() throws Exception {
		Vm vm = vm(compile(
				"public class Si { static final String A = \"lit\"; public static int go() {"
						+ " return (A == \"lit\") ? 1 : 0; } }"));
		assertEquals(1, run(vm, "Si", "go"));
	}
}
