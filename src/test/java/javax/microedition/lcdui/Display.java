package javax.microedition.lcdui;

import javax.microedition.midlet.MIDlet;
import java.util.HashMap;
import java.util.Map;

/** Minimal test stub of the MIDP Display. */
public class Display {
	private static final Map<MIDlet, Display> displays = new HashMap<>();

	/** The midlet this display was created for — used to verify identity. */
	public final MIDlet owner;
	private Displayable current;

	private Display(MIDlet owner) {
		this.owner = owner;
	}

	public static synchronized Display getDisplay(MIDlet m) {
		Display d = displays.get(m);
		if (d == null) {
			d = new Display(m);
			displays.put(m, d);
		}
		return d;
	}

	public void setCurrent(Displayable d) {
		current = d;
	}

	public Displayable getCurrent() {
		return current;
	}
}
