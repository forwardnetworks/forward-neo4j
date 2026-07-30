# Contributing

## Development

Use Java 21 and the repository Gradle wrapper:

```bash
./gradlew clean test jar
```

Before submitting a change:

1. Add or update harness tests for procedure and HTTP behavior.
2. Keep all fixtures synthetic; use documentation IP ranges such as `192.0.2.0/24`, `198.51.100.0/24`, and
   `203.0.113.0/24`.
3. Never commit credentials, tenant identifiers, snapshot IDs, live API responses, logs, graph exports, or live
   screenshots.
4. Run the complete test suite and inspect the JAR resource list.

## Adding an NQE

An NQE addition must include:

- an immutable versioned source under `src/main/resources/nqe`;
- a fixed registry entry in `NqeProjection`;
- fake async execution and result-page tests;
- a documented stable identity and graph mapping;
- an idempotent batched Cypher importer when the result belongs in the graph.

Do not expose arbitrary NQE source through a procedure. Add `-v2` when a contract's meaning or key changes.

## API additions

Prefer the specialized Forward API when it carries authoritative semantics. Checks, vulnerabilities, and path outcomes
should be snapshot-scoped observation nodes. Keep HTTP calls read-only unless a design explicitly reviews and documents
a remote side effect.
