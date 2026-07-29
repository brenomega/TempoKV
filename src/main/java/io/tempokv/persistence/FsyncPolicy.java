package io.tempokv.persistence;

/** Defines the durability point for a WAL append. */
public enum FsyncPolicy {
    /** Forces every acknowledged commit to stable storage. */
    ALWAYS,
    /** Leaves forcing to the operating system; useful only for explicitly relaxed durability. */
    NEVER
}
