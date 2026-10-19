package javax.microedition.lcdui;

import java.util.ArrayList;
import java.util.List;

/** Minimal test stub of the MIDP Displayable. */
public abstract class Displayable {
	public final List<Command> commands = new ArrayList<>();
	private CommandListener listener;
	private String title;

	public void setTitle(String t) {
		title = t;
	}

	public String getTitle() {
		return title;
	}

	public void addCommand(Command c) {
		commands.add(c);
	}

	public void setCommandListener(CommandListener l) {
		listener = l;
	}

	/** test helper: fire a command event as if the user invoked it. */
	public void fire(Command c) {
		if (listener != null)
			listener.commandAction(c, this);
	}
}
