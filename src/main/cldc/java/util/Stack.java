package java.util;

public class Stack extends Vector {

	public Stack() {
		super();
	}

	public Object push(Object item) {
		addElement(item);
		return item;
	}

	public synchronized Object pop() {
		if (elementCount == 0)
			throw new EmptyStackException();
		Object obj = elementData[--elementCount];
		elementData[elementCount] = null;
		return obj;
	}

	public synchronized Object peek() {
		if (elementCount == 0)
			throw new EmptyStackException();
		return elementData[elementCount - 1];
	}

	public boolean empty() {
		return elementCount == 0;
	}

	public synchronized int search(Object o) {
		int i = lastIndexOf(o);
		return i < 0 ? -1 : elementCount - i;
	}
}
