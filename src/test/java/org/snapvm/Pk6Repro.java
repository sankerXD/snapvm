package org.snapvm;

import java.util.zip.ZipFile;

/**
 * One-off repro (not a @Test): 宠物王国6 startup NPE —
 * CWA2DAPI.CWATools.readPngToByte("/data/tex/tex_N.mid") returns null on device.
 * Run: gradle testClasses, then
 *   java -cp build/classes/java/test:build/classes/java/main org.snapvm.Pk6Repro /tmp/pk6-res.jar
 */
public class Pk6Repro {
	public static void main(String[] a) throws Exception {
		String jar = a.length > 0 ? a[0] : "/tmp/pk6-res.jar";
		Vm vm = new Vm();
		vm.addJar(new ZipFile(jar));
		for (int i = 0; i < 4; i++) {
			String path = "/data/tex/tex_" + i + ".mid";
			try {
				Object r = vm.invokeStatic("CWA2DAPI/CWATools", "readPngToByte",
						"(Ljava/lang/String;)[B", path);
				if (r == null) {
					System.out.println(path + " -> null");
					continue;
				}
				byte[] png = (byte[]) r;
				String tag = "byte[" + png.length + "]";
				if (png.length >= 8 && (png[0] & 0xff) == 0x89 && png[1] == 'P'
						&& png[2] == 'N' && png[3] == 'G')
					tag += " PNG-MAGIC-OK";
				else
					tag += " BAD-MAGIC " + Integer.toHexString(png[0] & 0xff)
							+ " " + (png[1] & 0xff) + " " + (png[2] & 0xff);
				java.io.File out = new java.io.File("/tmp/pk6-tex_" + i + ".png");
				java.nio.file.Files.write(out.toPath(), png);
				java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(out);
				System.out.println(path + " -> " + tag + (img == null ? " ImageIO=null"
						: " decoded=" + img.getWidth() + "x" + img.getHeight()));
			} catch (Throwable t) {
				System.out.println(path + " -> THREW " + t);
			}
		}
	}
}
