package org.snapvm;

/** One interpreted call frame. Two-slot types occupy a value slot + a {@link #WIDE} marker. */
final class Frame {
	static final Object WIDE = new Object();

	VmClass owner;       // final until reset() for pool reuse
	ClassFile.Method method;
	final Object[] locals;
	final Object[] stack;
	int pc;
	int sp;              // next free stack slot
	int callPc = -1;     // pc of the invoke instruction that pushed our callee, if any
	boolean fromCall;    // this frame was pushed by an invoke* (not clinit/entry)
	boolean external;    // pushed via pushInvoke outside bytecode flow — its
	                     // return value goes to nestedResult, NOT the caller's
	                     // operand stack (the caller is mid-instruction and
	                     // never emitted a completing invoke)
	Object syncOn;       // monitor acquired for an ACC_SYNCHRONIZED method, or null

	Frame(VmClass owner, ClassFile.Method m) {
		this.owner = owner;
		this.method = m;
		this.locals = new Object[m.code.maxLocals];
		this.stack = new Object[m.code.maxStack + 1];
	}

	/** Rebind a pooled frame to a different method; arrays must fit. */
	void reset(VmClass owner, ClassFile.Method m) {
		this.owner = owner;
		this.method = m;
		pc = 0;
		sp = 0;
		callPc = -1;
		fromCall = false;
		external = false;
		syncOn = null;
		java.util.Arrays.fill(locals, null);
		java.util.Arrays.fill(stack, null);
	}

	String describe() {
		return owner.binaryName() + "." + method.name + method.desc + " @" + pc;
	}
}
