package de.smartdata.porter.importer;

/**
 * Exception thrown when a notifiction for an import could not be send or saved.
 * 
 * @author Florian
 */
public class ImportNotificationException extends Exception {

    /**
     * Creates a new instance of <code>ImportNotificationException</code>
     * without detail message.
     */
    public ImportNotificationException() {
    }

    /**
     * Constructs an instance of <code>ImportNotificationException</code> with
     * the specified detail message.
     *
     * @param msg the detail message.
     */
    public ImportNotificationException(String msg) {
        super(msg);
    }
}
