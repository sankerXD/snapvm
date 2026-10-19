package org.snapvm;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One-off harness (not a @Test): parse every .class in every jar under
 * /tmp/javalib/JAVA through ClassFile.parse to smoke out parser gaps on real
 * preverified/obfuscated J2ME bytecode.
 * Run: gradle testClasses, then java -cp build/classes/java/test:build/classes/java/main org.snapvm.JarParseHarness
 */
public class JarParseHarness {
	public static void main(String[] a) {
		File dir = new File(a.length > 0 ? a[0] : "/tmp/javalib/JAVA");
		File[] jars = dir.listFiles((d, n) -> n.endsWith(".jar"));
		int njars = 0, classes = 0, bad = 0, badJars = 0;
		for (File f : jars == null ? new File[0] : jars) {
			njars++;
			try (ZipFile z = new ZipFile(f)) {
				Enumeration<? extends ZipEntry> en = z.entries();
				while (en.hasMoreElements()) {
					ZipEntry e = en.nextElement();
					if (!e.getName().endsWith(".class"))
						continue;
					classes++;
					try (InputStream in = z.getInputStream(e)) {
						ClassFile.parse(readAll(in));
					} catch (Throwable t) {
						bad++;
						if (bad <= 30)
							System.out.println("FAIL " + f.getName() + " :: " + e.getName()
									+ " -> " + t);
					}
				}
			} catch (Throwable t) {
				badJars++;
				System.out.println("JAR FAIL " + f.getName() + " -> " + t);
			}
		}
		System.out.println("jars=" + njars + " badJars=" + badJars
				+ " classes=" + classes + " parseFailures=" + bad);
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) > 0)
			bos.write(buf, 0, n);
		return bos.toByteArray();
	}
}
