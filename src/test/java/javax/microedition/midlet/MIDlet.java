package javax.microedition.midlet;

import java.util.ArrayList;
import java.util.List;

/** Minimal test stub of the MIDP MIDlet — abstract like the real one. */
public abstract class MIDlet {
	/** Test probe: records notifyDestroyed() calls reaching the host. */
	public static final List<String> events = new ArrayList<>();

	protected abstract void startApp();
	protected abstract void pauseApp();
	protected abstract void destroyApp(boolean unconditional);

	public final void notifyDestroyed() {
		events.add("destroyed");
	}
}
