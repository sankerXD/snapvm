package java.util;

public class Hashtable {

	private Entry[] table;
	private int count;
	private int threshold;
	private final float loadFactor;

	private static class Entry {
		int hash;
		Object key;
		Object value;
		Entry next;
	}

	public Hashtable(int initialCapacity, float loadFactor) {
		if (initialCapacity < 0 || loadFactor <= 0)
			throw new IllegalArgumentException();
		if (initialCapacity == 0)
			initialCapacity = 1;
		this.loadFactor = loadFactor;
		table = new Entry[initialCapacity];
		threshold = (int) (initialCapacity * loadFactor);
	}

	public Hashtable(int initialCapacity) {
		this(initialCapacity, 0.75f);
	}

	public Hashtable() {
		this(11);
	}

	public synchronized int size() {
		return count;
	}

	public synchronized boolean isEmpty() {
		return count == 0;
	}

	public synchronized Enumeration keys() {
		return new TableEnum(true);
	}

	public synchronized Enumeration elements() {
		return new TableEnum(false);
	}

	private class TableEnum implements Enumeration {
		final boolean keys;
		int idx = table.length;
		Entry e;

		TableEnum(boolean keys) {
			this.keys = keys;
		}

		public boolean hasMoreElements() {
			for (Entry x = e; x != null || idx > 0;) {
				if (x == null)
					e = x = table[--idx];
				else
					return true;
			}
			return false;
		}

		public Object nextElement() {
			for (;;) {
				if (e == null) {
					if (idx == 0)
						throw new java.util.NoSuchElementException();
					e = table[--idx];
				} else {
					Entry r = e;
					e = e.next;
					return keys ? r.key : r.value;
				}
			}
		}
	}

	public synchronized boolean contains(Object value) {
		if (value == null)
			throw new NullPointerException();
		for (int i = table.length; i-- > 0;)
			for (Entry e = table[i]; e != null; e = e.next)
				if (e.value.equals(value))
					return true;
		return false;
	}

	public synchronized boolean containsValue(Object value) {
		return contains(value);
	}

	public synchronized boolean containsKey(Object key) {
		int h = key.hashCode();
		for (Entry e = table[(h & 0x7FFFFFFF) % table.length]; e != null; e = e.next)
			if (e.hash == h && e.key.equals(key))
				return true;
		return false;
	}

	public synchronized Object get(Object key) {
		int h = key.hashCode();
		for (Entry e = table[(h & 0x7FFFFFFF) % table.length]; e != null; e = e.next)
			if (e.hash == h && e.key.equals(key))
				return e.value;
		return null;
	}

	private void rehash() {
		Entry[] old = table;
		table = new Entry[old.length * 2 + 1];
		threshold = (int) (table.length * loadFactor);
		for (int i = old.length; i-- > 0;)
			for (Entry e = old[i]; e != null;) {
				Entry n = e.next;
				int idx = (e.hash & 0x7FFFFFFF) % table.length;
				e.next = table[idx];
				table[idx] = e;
				e = n;
			}
	}

	public synchronized Object put(Object key, Object value) {
		if (value == null)
			throw new NullPointerException();
		int h = key.hashCode();
		int idx = (h & 0x7FFFFFFF) % table.length;
		for (Entry e = table[idx]; e != null; e = e.next)
			if (e.hash == h && e.key.equals(key)) {
				Object old = e.value;
				e.value = value;
				return old;
			}
		if (count >= threshold) {
			rehash();
			idx = (h & 0x7FFFFFFF) % table.length;
		}
		Entry e = new Entry();
		e.hash = h;
		e.key = key;
		e.value = value;
		e.next = table[idx];
		table[idx] = e;
		count++;
		return null;
	}

	public synchronized Object remove(Object key) {
		int h = key.hashCode();
		int idx = (h & 0x7FFFFFFF) % table.length;
		Entry prev = null;
		for (Entry e = table[idx]; e != null; e = e.next) {
			if (e.hash == h && e.key.equals(key)) {
				if (prev == null)
					table[idx] = e.next;
				else
					prev.next = e.next;
				count--;
				return e.value;
			}
			prev = e;
		}
		return null;
	}

	public synchronized void clear() {
		for (int i = table.length; i-- > 0;)
			table[i] = null;
		count = 0;
	}

	public synchronized boolean equals(Object o) {
		if (o == this)
			return true;
		if (!(o instanceof Hashtable))
			return false;
		Hashtable t = (Hashtable) o;
		if (t.count != count)
			return false;
		for (int i = 0; i < table.length; i++)
			for (Entry e = table[i]; e != null; e = e.next) {
				Object v = t.get(e.key);
				if (e.value == null ? v != null : !e.value.equals(v))
					return false;
			}
		return true;
	}

	public synchronized int hashCode() {
		int h = 0;
		for (int i = 0; i < table.length; i++)
			for (Entry e = table[i]; e != null; e = e.next)
				h += e.hash + (e.value == null ? 0 : e.value.hashCode());
		return h;
	}

	public synchronized String toString() {
		StringBuilder b = new StringBuilder();
		b.append('{');
		boolean first = true;
		for (int i = 0; i < table.length; i++)
			for (Entry e = table[i]; e != null; e = e.next) {
				if (!first)
					b.append(", ");
				first = false;
				b.append(e.key).append('=').append(e.value);
			}
		b.append('}');
		return b.toString();
	}
}
