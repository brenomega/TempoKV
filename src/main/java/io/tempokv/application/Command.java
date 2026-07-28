package io.tempokv.application;

/**
 * Represents a protocol-independent request accepted by the application pipeline.
 */
public sealed interface Command permits AdminCommand, KeyValueCommand, TemporalCommand {
    /** Returns the command name used for validation, authorization, and metrics. */
    String name();
}
