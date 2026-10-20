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

public class VmSamplerTest {

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
		for (File f : dir.listFiles())
			if (f.getName().endsWith(".class"))
				out.put(f.getName().replace(".class", ""),
						Files.readAllBytes(f.toPath()));
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

	/**
	 * A worker thread runs a multi-second guest loop; the sampler must
	 * attribute samples to the interpreter AND to the running guest frame.
	 */
	@Test
	public void samplesAttributeGuestFrames() throws Exception {
		Vm vm = vm(compile(
				"public class Spin { public static int go() { long s = 0; "
						+ "for (long i = 0; i < 40000000L; i++) s += i & 7; "
						+ "return (int) s; } }"));
		Thread worker = new Thread(() -> vm.invokeStatic("Spin", "go", "()I"),
				"spin-worker");
		worker.start();
		VmSampler s = new VmSampler(vm);
		Map<String, Integer> counts = new HashMap<>();
		Map<String, Integer> cats = new HashMap<>();
		boolean sawInterp = false, sawGuest = false;
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline
				&& !(sawInterp && sawGuest)) {
			s.sampleOnce(counts, cats);
			for (String k : counts.keySet()) {
				if (k.contains("Interpreter"))
					sawInterp = true;
				if (k.contains("| guest Spin.go"))
					sawGuest = true;
			}
			Thread.sleep(5);
		}
		worker.join(30_000);
		assertTrue("sampler never saw the interpreter", sawInterp);
		assertTrue("no guest-frame attribution on executor", sawGuest);
		assertFalse("category rollup stayed empty", cats.isEmpty());
	}
}
