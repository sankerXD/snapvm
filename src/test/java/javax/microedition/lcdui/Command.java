package javax.microedition.lcdui;

/** Minimal test stub of the MIDP Command. */
public class Command {
	private final String label;
	private final int commandType;
	private final int priority;

	public Command(String label, int commandType, int priority) {
		this.label = label;
		this.commandType = commandType;
		this.priority = priority;
	}

	public String getLabel() {
		return label;
	}
}
