package io.tempokv.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Selects retained history by default and by the longest matching key prefix.
 *
 * <p>A key always retains its newest version. Versions needed by an active snapshot are
 * also retained, even when they are older than the normal policy.</p>
 */
public final class RetentionPolicy {
    /** Defines the age and count bounds for a matching key scope. */
    public record Rule(int maxVersions, Duration maxAge) {
        /** Validates explicit, positive retention bounds. */
        public Rule {
            if (maxVersions < 1) throw new IllegalArgumentException("maxVersions must be positive");
            maxAge = Objects.requireNonNull(maxAge, "maxAge");
            if (maxAge.isNegative() || maxAge.isZero()) throw new IllegalArgumentException("maxAge must be positive");
        }
    }
    private final Rule defaultRule;
    private final Map<String, Rule> prefixes;

    /** Creates a policy with a default rule and optional prefix-specific overrides. */
    public RetentionPolicy(Rule defaultRule, Map<String, Rule> prefixes) {
        this.defaultRule = Objects.requireNonNull(defaultRule, "defaultRule");
        this.prefixes = Map.copyOf(Objects.requireNonNull(prefixes, "prefixes"));
        if (this.prefixes.keySet().stream().anyMatch(String::isEmpty)) throw new IllegalArgumentException("Retention prefix must not be empty");
    }

    /** Returns the rule selected for a key using longest-prefix matching. */
    public Rule ruleFor(String key) {
        Objects.requireNonNull(key, "key");
        return prefixes.entrySet().stream().filter(entry -> key.startsWith(entry.getKey()))
                .max(Comparator.comparingInt(entry -> entry.getKey().length())).map(Map.Entry::getValue).orElse(defaultRule);
    }

    /** Returns versions safe to retain under policy and an optional oldest active snapshot version. */
    public List<VersionedValue> retain(String key, List<VersionedValue> versions, Instant now, long oldestSnapshotVersion) {
        Objects.requireNonNull(now, "now");
        List<VersionedValue> retainedCandidates = List.copyOf(Objects.requireNonNull(versions, "versions"));
        if (retainedCandidates.isEmpty()) return List.of();
        Rule rule = ruleFor(key);
        int normalLimit = 1;
        Instant ageBoundary = now.minus(rule.maxAge());
        while (normalLimit < retainedCandidates.size()
                && normalLimit < rule.maxVersions()
                && !retainedCandidates.get(normalLimit).committedAt().isBefore(ageBoundary)) {
            normalLimit++;
        }
        int snapshotVisibleIndex = oldestSnapshotVersion > 0
                ? java.util.stream.IntStream.range(0, retainedCandidates.size()).filter(index -> retainedCandidates.get(index).version() <= oldestSnapshotVersion).findFirst().orElse(-1)
                : -1;
        int retainedCount = Math.max(normalLimit, snapshotVisibleIndex + 1);
        return retainedCandidates.subList(0, retainedCount);
    }
}
