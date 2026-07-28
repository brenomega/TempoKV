package io.tempokv.storage;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Associates each logical key with its immutable MVCC version chain. */
public final class KeyIndex {
    private final ConcurrentHashMap<String, VersionChain> chains = new ConcurrentHashMap<>();

    /** Returns the current chain for a key, if it has ever been written. */
    public Optional<VersionChain> get(String key) { return Optional.ofNullable(chains.get(key)); }

    /** Publishes a completely built immutable chain for a key. */
    public void put(String key, VersionChain chain) { chains.put(key, chain); }
}
