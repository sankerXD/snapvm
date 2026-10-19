package org.snapvm;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal .class parser covering the J2ME-era format (also tolerant of newer
 * constant-pool tags so desktop-compiled fixtures parse fine).
 */
final class ClassFile {
	String name;
	String superName;
	String[] interfaces;
	int access;
	Field[] fields = new Field[0];
	Method[] methods = new Method[0];
	private Object[] cp; // Utf8->String, Integer/Float/Long/Double, Ref, NameAndType, class-idx Integer
	private int[] cpTag;

	static final class Field {
		int access;
		String name, desc;
		Object constantValue; // Integer/Long/Float/Double/String or null
	}

	static final class Method {
		int access;
		String name, desc;
		Code code; // null for abstract/native
	}

	static final class Code {
		int maxStack, maxLocals;
		byte[] code;
		ExEntry[] exTable = new ExEntry[0];
	}

	static final class ExEntry {
		int startPc, endPc, handlerPc;
		String catchType; // null = catch-all
	}

	static final class Ref {
		int tag; // 9 field, 10 method, 11 iface method
		String owner, name, desc;
		private int argSlots = -1, argCount = -1, retKind = -1;
		String key;          // name|desc — methodMap/declaredMethods lookup key
		// resolved caches — classes never unload, so bindings stay valid
		VmClass ownerClass;
		int fieldSlot = -1;              // guest-field flattened slot
		VmClass staticOwner;             // guest static field owner
		int staticSlot = -1;
		VmClass cacheRecv;               // monomorphic inline cache for virtuals
		ClassFile.Method cacheMethod;
		VmClass cacheDecl;

		int argSlots() {
			if (argSlots < 0)
				argSlots = VmClass.argSlots(desc);
			return argSlots;
		}

		int argCount() {
			if (argCount < 0)
				argCount = VmClass.countArgs(desc);
			return argCount;
		}

		/** 0 = void, 1 = single-slot, 2 = wide return. */
		int retKind() {
			if (retKind < 0) {
				String rt = VmClass.returnType(desc);
				retKind = rt.equals("V") ? 0 : VmClass.isWide(rt) ? 2 : 1;
			}
			return retKind;
		}

		String key() {
			if (key == null)
				key = name + "|" + desc;
			return key;
		}
	}

	static final class NameAndType {
		String name, desc;
	}

	String utf(int i) {
		return (String) cp[i];
	}

	String className(int i) {
		return utf((Integer) cp[i]);
	}

	/** Raw constant-pool value for ldc (String/Integer/Float/Long/Double; tag 7 = class literal idx). */
	Object cpValue(int i) {
		return cp[i];
	}

	int cpTag(int i) {
		return cpTag[i];
	}

	static ClassFile parse(byte[] bytes) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
		if (in.readInt() != 0xCAFEBABE)
			throw new IOException("not a class file");
		in.readUnsignedShort(); // minor
		in.readUnsignedShort(); // major
		ClassFile cf = new ClassFile();
		int n = in.readUnsignedShort();
		cf.cp = new Object[n];
		cf.cpTag = new int[n];
		int[] rawA = new int[n], rawB = new int[n];
		for (int i = 1; i < n; i++) {
			int tag = in.readUnsignedByte();
			cf.cpTag[i] = tag;
			switch (tag) {
				case 1: cf.cp[i] = in.readUTF(); break;
				case 3: cf.cp[i] = in.readInt(); break;
				case 4: cf.cp[i] = in.readFloat(); break;
				case 5: cf.cp[i] = in.readLong(); i++; break;
				case 6: cf.cp[i] = in.readDouble(); i++; break;
				case 7: case 8: case 16: case 19: case 20:
					rawA[i] = in.readUnsignedShort(); break;
				case 9: case 10: case 11: case 12: case 17: case 18:
					rawA[i] = in.readUnsignedShort(); rawB[i] = in.readUnsignedShort(); break;
				case 15: in.readUnsignedByte(); in.readUnsignedShort(); break;
				default: throw new IOException("bad cp tag " + tag);
			}
		}
		// second pass: resolve leaf entries first (refs can forward-reference them)
		for (int i = 1; i < n; i++) {
			switch (cf.cpTag[i]) {
				case 7: cf.cp[i] = rawA[i]; break;              // Class -> utf index (kept as Integer)
				case 8: cf.cp[i] = cf.utf(rawA[i]); break;      // String -> value
				case 12:                                        // NameAndType
					NameAndType nat = new NameAndType();
					nat.name = cf.utf(rawA[i]);
					nat.desc = cf.utf(rawB[i]);
					cf.cp[i] = nat;
					break;
			}
		}
		for (int i = 1; i < n; i++) {
			if (cf.cpTag[i] == 9 || cf.cpTag[i] == 10 || cf.cpTag[i] == 11) {
				Ref r = new Ref();
				r.tag = cf.cpTag[i];
				r.owner = cf.className(rawA[i]);
				NameAndType nt = (NameAndType) cf.cp[rawB[i]];
				r.name = nt.name;
				r.desc = nt.desc;
				cf.cp[i] = r;
			}
		}
		cf.access = in.readUnsignedShort();
		cf.name = cf.className(in.readUnsignedShort());
		int sc = in.readUnsignedShort();
		cf.superName = sc == 0 ? null : cf.className(sc);
		int ic = in.readUnsignedShort();
		cf.interfaces = new String[ic];
		for (int i = 0; i < ic; i++) cf.interfaces[i] = cf.className(in.readUnsignedShort());
		int fc = in.readUnsignedShort();
		List<Field> fl = new ArrayList<>(fc);
		for (int i = 0; i < fc; i++) fl.add(readField(cf, in));
		cf.fields = fl.toArray(new Field[0]);
		int mc = in.readUnsignedShort();
		List<Method> ml = new ArrayList<>(mc);
		for (int i = 0; i < mc; i++) ml.add(readMethod(cf, in));
		cf.methods = ml.toArray(new Method[0]);
		skipAttributes(cf, in); // class-level attrs ignored
		return cf;
	}

	private static Field readField(ClassFile cf, DataInputStream in) throws IOException {
		Field f = new Field();
		f.access = in.readUnsignedShort();
		f.name = cf.utf(in.readUnsignedShort());
		f.desc = cf.utf(in.readUnsignedShort());
		int ac = in.readUnsignedShort();
		for (int i = 0; i < ac; i++) {
			String attr = cf.utf(in.readUnsignedShort());
			long len = in.readInt() & 0xffffffffL;
			if ("ConstantValue".equals(attr)) {
				f.constantValue = cf.cp[in.readUnsignedShort()];
			} else if (in.skip(len) != len) {
				throw new EOFException();
			}
		}
		return f;
	}

	private static Method readMethod(ClassFile cf, DataInputStream in) throws IOException {
		Method m = new Method();
		m.access = in.readUnsignedShort();
		m.name = cf.utf(in.readUnsignedShort());
		m.desc = cf.utf(in.readUnsignedShort());
		int ac = in.readUnsignedShort();
		for (int i = 0; i < ac; i++) {
			String attr = cf.utf(in.readUnsignedShort());
			int len = in.readInt();
			if ("Code".equals(attr)) {
				Code c = new Code();
				c.maxStack = in.readUnsignedShort();
				c.maxLocals = in.readUnsignedShort();
				int cl = in.readInt();
				c.code = new byte[cl];
				in.readFully(c.code);
				int ec = in.readUnsignedShort();
				c.exTable = new ExEntry[ec];
				for (int e = 0; e < ec; e++) {
					ExEntry ex = new ExEntry();
					ex.startPc = in.readUnsignedShort();
					ex.endPc = in.readUnsignedShort();
					ex.handlerPc = in.readUnsignedShort();
					int ct = in.readUnsignedShort();
					ex.catchType = ct == 0 ? null : cf.className(ct);
					c.exTable[e] = ex;
				}
				skipAttributes(cf, in); // nested code attrs (LineNumberTable etc.)
				m.code = c;
			} else {
				if (in.skip(len) != len) throw new EOFException();
			}
		}
		return m;
	}

	private static void skipAttributes(ClassFile cf, DataInputStream in) throws IOException {
		int ac = in.readUnsignedShort();
		for (int i = 0; i < ac; i++) {
			cf.utf(in.readUnsignedShort());
			long len = in.readInt() & 0xffffffffL;
			if (in.skip(len) != len) throw new EOFException();
		}
	}
}
