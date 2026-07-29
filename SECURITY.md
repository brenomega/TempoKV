# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| 0.1.x | Yes |
| < 0.1.0 | No |

## Reporting a vulnerability

Do not disclose a suspected vulnerability in a public GitHub issue.

If private vulnerability reporting is enabled for
`brenomega/TempoKV`, use the repository's **Security** tab to submit a private
report. Otherwise, use a private draft Security Advisory in the repository.
The maintainer should enable private vulnerability reporting in the GitHub
repository settings if it is not already enabled.

Do not include passwords, replication tokens, private keys, production data, or
other secrets in a report.

## Expected report content

Please include:

- the affected TempoKV version or commit;
- the affected component;
- a minimal reproduction;
- the security impact;
- relevant configuration with all secrets removed;
- a proposed mitigation, if one is known.

## Security scope and deployment boundary

TempoKV has no native TLS and does not support direct exposure to the public
Internet. Run it on loopback or a trusted private network, or place it behind a
proxy, tunnel, or service mesh that terminates TLS.

Authentication credentials and the replication secret must be configured
explicitly when their features are enabled. A replica has no automatic failover,
election, or promotion mechanism.

TempoKV is a finished technical project and reference implementation, not a
security certification or a claim of production readiness.
