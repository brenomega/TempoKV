package io.tempokv.security;

import io.tempokv.application.Command;
import io.tempokv.application.KeyValueCommand;
import io.tempokv.application.TemporalCommand;
import io.tempokv.server.Session;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Authorizes a session to execute a command. */
@FunctionalInterface
public interface AccessController {
    /** Returns whether a session may execute the command. */
    boolean isAllowed(Session session, Command command);

    /** Returns the protocol-neutral denial message for a rejected command. */
    default String denialMessage(Session session, Command command) {
        return "ERR command is not permitted";
    }

    /** Returns a permissive policy for focused tests and explicitly open endpoints. */
    static AccessController permissive() { return (session, command) -> true; }

    /**
     * Creates an identity-based ACL with command names and allowed key prefixes.
     *
     * <p>Unknown or anonymous identities are denied. Commands without a key only require their
     * normalized name to be present in the identity's rule.</p>
     */
    static AccessController rules(Map<String, Rule> rules) {
        return rules(rules, "ERR command is not permitted");
    }

    /** Creates identity rules with an explicit client-safe denial message. */
    static AccessController rules(
            Map<String, Rule> rules, String denialMessage) {
        Map<String, Rule> copied = Map.copyOf(Objects.requireNonNull(rules, "rules"));
        String denied = Objects.requireNonNull(denialMessage, "denialMessage");
        return new AccessController() {
            @Override public boolean isAllowed(Session session, Command command) {
                return session.identity()
                        .map(copied::get)
                        .filter(Objects::nonNull)
                        .map(rule -> rule.allows(command))
                        .orElse(false);
            }
            @Override public String denialMessage(Session session, Command command) {
                return denied;
            }
        };
    }

    /** Defines the command names and key prefixes authorized for one identity. */
    record Rule(Set<String> commands, Set<String> keyPrefixes) {
        /** Normalizes command names and copies the configured authorization scopes. */
        public Rule {
            commands = Objects.requireNonNull(commands, "commands").stream()
                    .map(name -> Objects.requireNonNull(name, "command")
                            .toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            keyPrefixes = Set.copyOf(
                    Objects.requireNonNull(keyPrefixes, "keyPrefixes"));
            if (commands.isEmpty()) {
                throw new IllegalArgumentException("ACL rule requires commands");
            }
            if (keyPrefixes.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("ACL prefix must not be null");
            }
        }

        /** Returns whether both the command and its optional key scope are authorized. */
        public boolean allows(Command command) {
            Objects.requireNonNull(command, "command");
            if (!commands.contains(command.name().toUpperCase(Locale.ROOT))) {
                return false;
            }
            String key = switch (command) {
                case KeyValueCommand keyValue -> keyValue.key();
                case TemporalCommand temporal -> temporal.key();
                default -> null;
            };
            return key == null || keyPrefixes.stream().anyMatch(key::startsWith);
        }
    }
}
