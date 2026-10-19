package java.util;

public class Vector {

	protected Object[] elementData;
	protected int elementCount;
	protected int capacityIncrement;

	public Vector(int initialCapacity, int capacityIncrement) {
		if (initialCapacity < 0)
			throw new IllegalArgumentException();
		elementData = new Object[initialCapacity];
		this.capacityIncrement = capacityIncrement;
	}

	public Vector(int initialCapacity) {
		this(initialCapacity, 0);
	}

	public Vector() {
		this(10);
	}

	public synchronized void copyInto(Object[] anArray) {
		System.arraycopy(elementData, 0, anArray, 0, elementCount);
	}

	public synchronized void trimToSize() {
		if (elementData.length > elementCount) {
			Object[] nd = new Object[elementCount];
			System.arraycopy(elementData, 0, nd, 0, elementCount);
			elementData = nd;
		}
	}

	public synchronized void ensureCapacity(int minCapacity) {
		if (minCapacity > elementData.length)
			grow(minCapacity);
	}

	private void grow(int minCapacity) {
		int old = elementData.length;
		int n = capacityIncrement > 0 ? old + capacityIncrement : old * 2;
		if (n < minCapacity)
			n = minCapacity;
		Object[] nd = new Object[n];
		System.arraycopy(elementData, 0, nd, 0, elementCount);
		elementData = nd;
	}

	public synchronized void setSize(int newSize) {
		if (newSize > elementCount)
			ensureCapacity(newSize);
		else
			for (int i = newSize; i < elementCount; i++)
				elementData[i] = null;
		elementCount = newSize;
	}

	public synchronized int capacity() {
		return elementData.length;
	}

	public synchronized int size() {
		return elementCount;
	}

	public synchronized boolean isEmpty() {
		return elementCount == 0;
	}

	public synchronized Enumeration elements() {
		return new VectorEnum();
	}

	private class VectorEnum implements Enumeration {
		int i;

		public boolean hasMoreElements() {
			return i < elementCount;
		}

		public Object nextElement() {
			if (i >= elementCount)
				throw new java.util.NoSuchElementException();
			return elementData[i++];
		}
	}

	public boolean contains(Object elem) {
		return indexOf(elem, 0) >= 0;
	}

	public int indexOf(Object elem) {
		return indexOf(elem, 0);
	}

	public synchronized int indexOf(Object elem, int index) {
		if (elem == null) {
			for (int i = index; i < elementCount; i++)
				if (elementData[i] == null)
					return i;
		} else {
			for (int i = index; i < elementCount; i++)
				if (elem.equals(elementData[i]))
					return i;
		}
		return -1;
	}

	public synchronized int lastIndexOf(Object elem) {
		if (elem == null) {
			for (int i = elementCount - 1; i >= 0; i--)
				if (elementData[i] == null)
					return i;
		} else {
			for (int i = elementCount - 1; i >= 0; i--)
				if (elem.equals(elementData[i]))
					return i;
		}
		return -1;
	}

	public synchronized Object elementAt(int index) {
		if (index >= elementCount)
			throw new ArrayIndexOutOfBoundsException(index);
		return elementData[index];
	}

	public synchronized Object firstElement() {
		if (elementCount == 0)
			throw new java.util.NoSuchElementException();
		return elementData[0];
	}

	public synchronized Object lastElement() {
		if (elementCount == 0)
			throw new java.util.NoSuchElementException();
		return elementData[elementCount - 1];
	}

	public synchronized void setElementAt(Object obj, int index) {
		if (index >= elementCount)
			throw new ArrayIndexOutOfBoundsException(index);
		elementData[index] = obj;
	}

	public synchronized void removeElementAt(int index) {
		if (index >= elementCount)
			throw new ArrayIndexOutOfBoundsException(index);
		int j = elementCount - index - 1;
		if (j > 0)
			System.arraycopy(elementData, index + 1, elementData, index, j);
		elementData[--elementCount] = null;
	}

	public synchronized void insertElementAt(Object obj, int index) {
		if (index > elementCount)
			throw new ArrayIndexOutOfBoundsException(index);
		ensureCapacity(elementCount + 1);
		System.arraycopy(elementData, index, elementData, index + 1, elementCount - index);
		elementData[index] = obj;
		elementCount++;
	}

	public synchronized void addElement(Object obj) {
		ensureCapacity(elementCount + 1);
		elementData[elementCount++] = obj;
	}

	public synchronized boolean removeElement(Object obj) {
		int i = indexOf(obj);
		if (i < 0)
			return false;
		removeElementAt(i);
		return true;
	}

	public synchronized void removeAllElements() {
		for (int i = 0; i < elementCount; i++)
			elementData[i] = null;
		elementCount = 0;
	}

	public synchronized Object clone() {
		Vector v = new Vector(elementData.length, capacityIncrement);
		v.elementCount = elementCount;
		System.arraycopy(elementData, 0, v.elementData, 0, elementCount);
		return v;
	}

	public synchronized Object[] toArray() {
		Object[] a = new Object[elementCount];
		System.arraycopy(elementData, 0, a, 0, elementCount);
		return a;
	}

	public synchronized boolean add(Object o) {
		addElement(o);
		return true;
	}

	public synchronized Object get(int index) {
		return elementAt(index);
	}

	public synchronized boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof Vector))
			return false;
		Vector v = (Vector) o;
		if (v.elementCount != elementCount)
			return false;
		for (int i = 0; i < elementCount; i++) {
			Object a = elementData[i], b = v.elementData[i];
			if (a == null ? b != null : !a.equals(b))
				return false;
		}
		return true;
	}

	public synchronized int hashCode() {
		int h = 1;
		for (int i = 0; i < elementCount; i++)
			h = 31 * h + (elementData[i] == null ? 0 : elementData[i].hashCode());
		return h;
	}

	public synchronized String toString() {
		StringBuilder b = new StringBuilder();
		b.append('[');
		for (int i = 0; i < elementCount; i++) {
			if (i > 0)
				b.append(", ");
			Object e = elementData[i];
			b.append(e == this ? "(this Collection)" : e);
		}
		b.append(']');
		return b.toString();
	}
}
