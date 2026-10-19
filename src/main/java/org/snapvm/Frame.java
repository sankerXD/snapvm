package org.snapvm;

/** One interpreted call frame. Two-slot types occupy a value slot + a {@link #WIDE} marker. */
final class Frame {
	static final Object WIDE = new Object();

	final VmClass owner;
	final ClassFile.Method method;
	final Object[] locals;
	final Object[] stack;
	int pc;
	int sp;              // next free stack slot
	int callPc = -1;     // pc of the invoke instruction that pushed our callee, if any
	boolean fromCall;    // this frame was pushed by an invoke* (not clinit/entry)

	Frame(VmClass owner, ClassFile.Method m) {
		this.owner = owner;
		this.method = m;
		this.locals = new Object[m.code.maxLocals];
		this.stack = new Object[m.code.maxStack + 1];
	}

	String describe() {
		return owner.binaryName() + "." + method.name + method.desc + " @" + pc;
	}
}
