package io.tempokv.observability;

/**
 * Lists the externally observable lifecycle and health states of a TempoKV node.
 */
public enum ServerHealth {
    STARTING,
    RECOVERING,
    READY,
    DEGRADED,
    STOPPING
}
