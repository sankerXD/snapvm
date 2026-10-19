package java.lang;

public class StringBuilder {

	private char[] value;
	private int count;

	public StringBuilder() {
		this(16);
	}

	public StringBuilder(int capacity) {
		value = new char[capacity < 0 ? 16 : capacity];
	}

	public StringBuilder(String str) {
		this(str.length() + 16);
		append(str);
	}

	public StringBuilder(Object obj) {
		this(String.valueOf(obj));
	}

	public int length() {
		return count;
	}

	public int capacity() {
		return value.length;
	}

	public void ensureCapacity(int min) {
		if (min > value.length)
			grow(min);
	}

	private void grow(int min) {
		int n = value.length * 2 + 2;
		if (n < min)
			n = min;
		char[] nv = new char[n];
		System.arraycopy(value, 0, nv, 0, count);
		value = nv;
	}

	public void setLength(int newLength) {
		if (newLength < 0)
			throw new IndexOutOfBoundsException();
		ensureCapacity(newLength);
		if (count < newLength)
			for (int i = count; i < newLength; i++)
				value[i] = '\0';
		count = newLength;
	}

	public char charAt(int index) {
		if (index < 0 || index >= count)
			throw new IndexOutOfBoundsException();
		return value[index];
	}

	public void getChars(int srcBegin, int srcEnd, char[] dst, int dstBegin) {
		if (srcBegin < 0 || srcEnd > count || srcBegin > srcEnd)
			throw new IndexOutOfBoundsException();
		System.arraycopy(value, srcBegin, dst, dstBegin, srcEnd - srcBegin);
	}

	public void setCharAt(int index, char ch) {
		if (index < 0 || index >= count)
			throw new IndexOutOfBoundsException();
		value[index] = ch;
	}

	public StringBuilder append(Object obj) {
		return append(obj == null ? "null" : obj.toString());
	}

	public StringBuilder append(String str) {
		if (str == null)
			str = "null";
		int len = str.length();
		ensureCapacity(count + len);
		str.getChars(0, len, value, count);
		count += len;
		return this;
	}

	public StringBuilder append(StringBuffer sb) {
		if (sb == null)
			return append("null");
		int len = sb.length();
		ensureCapacity(count + len);
		sb.getChars(0, len, value, count);
		count += len;
		return this;
	}

	public StringBuilder append(char[] str) {
		ensureCapacity(count + str.length);
		System.arraycopy(str, 0, value, count, str.length);
		count += str.length;
		return this;
	}

	public StringBuilder append(char[] str, int offset, int len) {
		ensureCapacity(count + len);
		System.arraycopy(str, offset, value, count, len);
		count += len;
		return this;
	}

	public StringBuilder append(boolean b) {
		return append(b ? "true" : "false");
	}

	public StringBuilder append(char c) {
		ensureCapacity(count + 1);
		value[count++] = c;
		return this;
	}

	public StringBuilder append(int i) {
		return append(String.valueOf(i));
	}

	public StringBuilder append(long l) {
		return append(String.valueOf(l));
	}

	public StringBuilder append(float f) {
		return append(String.valueOf(f));
	}

	public StringBuilder append(double d) {
		return append(String.valueOf(d));
	}

	public StringBuilder delete(int start, int end) {
		if (start < 0 || start > count || start > end)
			throw new StringIndexOutOfBoundsException(start);
		if (end > count)
			end = count;
		int len = count - end;
		if (len > 0)
			System.arraycopy(value, end, value, start, len);
		count -= end - start;
		return this;
	}

	public StringBuilder deleteCharAt(int index) {
		if (index < 0 || index >= count)
			throw new StringIndexOutOfBoundsException(index);
		System.arraycopy(value, index + 1, value, index, count - index - 1);
		count--;
		return this;
	}

	public StringBuilder replace(int start, int end, String str) {
		if (start < 0 || start > count)
			throw new StringIndexOutOfBoundsException(start);
		if (end > count)
			end = count;
		int len = str.length();
		int newCount = count + len - (end - start);
		ensureCapacity(newCount);
		System.arraycopy(value, end, value, start + len, count - end);
		str.getChars(0, len, value, start);
		count = newCount;
		return this;
	}

	public StringBuilder insert(int index, char[] str, int offset, int len) {
		if (index < 0 || index > count)
			throw new StringIndexOutOfBoundsException(index);
		ensureCapacity(count + len);
		System.arraycopy(value, index, value, index + len, count - index);
		System.arraycopy(str, offset, value, index, len);
		count += len;
		return this;
	}

	public StringBuilder insert(int index, Object obj) {
		return insert(index, obj == null ? "null" : obj.toString());
	}

	public StringBuilder insert(int index, String str) {
		if (index < 0 || index > count)
			throw new StringIndexOutOfBoundsException(index);
		if (str == null)
			str = "null";
		int len = str.length();
		ensureCapacity(count + len);
		System.arraycopy(value, index, value, index + len, count - index);
		str.getChars(0, len, value, index);
		count += len;
		return this;
	}

	public StringBuilder insert(int index, char[] str) {
		return insert(index, str, 0, str.length);
	}

	public StringBuilder insert(int index, boolean b) {
		return insert(index, b ? "true" : "false");
	}

	public StringBuilder insert(int index, char c) {
		ensureCapacity(count + 1);
		System.arraycopy(value, index, value, index + 1, count - index);
		value[index] = c;
		count++;
		return this;
	}

	public StringBuilder insert(int index, int i) {
		return insert(index, String.valueOf(i));
	}

	public StringBuilder insert(int index, long l) {
		return insert(index, String.valueOf(l));
	}

	public StringBuilder insert(int index, float f) {
		return insert(index, String.valueOf(f));
	}

	public StringBuilder insert(int index, double d) {
		return insert(index, String.valueOf(d));
	}

	public int indexOf(String str) {
		return indexOf(str, 0);
	}

	public int indexOf(String str, int fromIndex) {
		int n = str.length();
		for (int i = fromIndex < 0 ? 0 : fromIndex; i + n <= count; i++) {
			boolean m = true;
			for (int j = 0; j < n; j++)
				if (value[i + j] != str.charAt(j)) {
					m = false;
					break;
				}
			if (m)
				return i;
		}
		return -1;
	}

	public int lastIndexOf(String str) {
		int n = str.length();
		for (int i = count - n; i >= 0; i--) {
			boolean m = true;
			for (int j = 0; j < n; j++)
				if (value[i + j] != str.charAt(j)) {
					m = false;
					break;
				}
			if (m)
				return i;
		}
		return -1;
	}

	public StringBuilder reverse() {
		for (int i = 0, j = count - 1; i < j; i++, j--) {
			char c = value[i];
			value[i] = value[j];
			value[j] = c;
		}
		return this;
	}

	public String substring(int start) {
		return substring(start, count);
	}

	public String substring(int start, int end) {
		if (start < 0 || end > count || start > end)
			throw new StringIndexOutOfBoundsException(start);
		return new String(value, start, end - start);
	}

	public CharSequence subSequence(int start, int end) {
		return substring(start, end);
	}

	public String toString() {
		return new String(value, 0, count);
	}
}
