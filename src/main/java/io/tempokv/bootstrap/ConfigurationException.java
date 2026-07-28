package io.tempokv.bootstrap;

/**
 * Reports invalid or unreadable node configuration before server resources are opened.
 */
public final class ConfigurationException extends IllegalArgumentException {
    /** Creates a configuration failure with a safe operator-facing message. */
    public ConfigurationException(String message) {
        super(message);
    }

    /** Creates a configuration failure while preserving its infrastructure cause. */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
