# Forward-to-Neo4j engineering handoff

Status: `0.2.0` reference integration prepared for initial release

Prepared: 2026-07-30

## Outcome

The repository contains a Java 21 Neo4j plugin for publishing selected Forward Networks API results into a
snapshot-scoped property graph. It uses supported read-only data APIs and Forward's asynchronous NQE execution surface.
No tenant data, tenant identifiers, credentials, or live-environment screenshots belong in this repository.

## Implemented procedure surface

```cypher
CALL forward.plugin.info();
CALL forward.license.features();
CALL forward.testConnection();
CALL forward.schema.install();
CALL forward.import.snapshot("network-id", "snapshot-id");
CALL forward.nqe.projections();
CALL forward.nqe.stream("bgp-neighbors-v1", "network-id", "snapshot-id");
CALL forward.checks.stream("network-id", "snapshot-id");
CALL forward.checks.detail("check-id", "network-id", "snapshot-id");
CALL forward.vulnerabilities.stream("network-id", "snapshot-id");
CALL forward.vulnerabilities.detail("cve-id", "network-id", "snapshot-id");
CALL forward.paths.stream($flows, $options, "network-id", "snapshot-id");
```

`forward.nqe.stream` is a Neo4j read-mode procedure. The remote side effect is limited to creating a Forward NQE
execution record: the procedure submits a bundled query, polls its status, and lazily reads materialized result pages.
It does not modify Forward network data.

## Key decisions

1. **Async NQE only.** Submit through `nqe-executions`, poll no more often than every five seconds, then page the
   materialized result. Submission POST is not retried because a lost response could otherwise create a duplicate
   execution with an unknown first key.
2. **Fixed extraction contracts.** NQE source is shipped under `src/main/resources/nqe`, included in the plugin JAR,
   and identified by name, semantic version, and SHA-256. Arbitrary NQE input is intentionally unavailable.
3. **Small graph contracts.** Each NQE has explicit output fields, stable identity, and a paired Cypher importer. A
   meaning or key change creates a new `-v2` projection rather than silently changing `-v1`.
4. **Snapshot isolation.** Imported fact identities include `networkId` and `snapshotId`; observation identities add
   their natural source keys. This permits multi-snapshot comparison without overwriting history.
5. **Preserve observations.** A BGP neighbor is a directional observation. A higher-level peer link is added only when
   Forward supplies peer identity; it is never inferred from an address alone.
6. **Bounded transactions.** NQE scripts use `CALL { ... } IN TRANSACTIONS OF 5000 ROWS ON ERROR RETRY`. The import is
   restartable and idempotent, but intentionally not globally atomic.
7. **API specialization.** NQE handles modeled structure. Direct Checks, vulnerability, and Bulk Path Search APIs
   preserve their authoritative result semantics.
8. **License-aware selection.** The explicit `B`, `N`, `NP`, `S`, `NS`, or `NSP` selector gates calls locally. Base
   gets inventory plus compatible interface NQEs; routing NQEs require Network; Checks/Paths require Network or
   Security; vulnerabilities require Security.

## Runtime and security behavior

- `FORWARD_CREDENTIAL_FILE` must be a regular POSIX file with no group/other permissions.
- Non-loopback Forward URLs require HTTPS, and HTTP redirects are disabled.
- GET retries are limited to transient 429/502/503/504 responses and I/O failures.
- Cancellation from Neo4j is checked during NQE polling and page consumption.
- Connection tests return no network IDs or names.
- The deployable service is localhost-only by default. LAN access is an explicit connector plus firewall configuration.

## Verification baseline

The harness suite covers procedure registration, fixed projection enumeration, protected credential files,
asynchronous submit/status handling, and lazy multi-page result retrieval. The release gate is:

```bash
./gradlew clean test jar
jar tf build/libs/forward-neo4j-plugin-0.2.0.jar
```

The JAR must contain all four `.nqe` resources. Release screenshots must be captured from `demo/synthetic-data.cypher`
in an isolated Neo4j home, never from an existing graph.

## Known gaps

1. `forward.import.snapshot()` downloads and writes base inventory/topology in one transaction.
2. Projection-run markers are updated per row, which adds lock traffic.
3. A zero-row NQE stream currently creates no graph marker.
4. There is no validation/publication job state machine or pruning policy.
5. Check diagnoses and CVE device details are exposed on demand but are not bulk-imported.
6. Path imports preserve hop order but do not yet materialize a separate `NEXT_HOP` relationship chain.
7. AuraDB requires an external connector rather than this self-managed plugin.

## Next build order

1. Add a projection-job node with `SUBMITTED`, `IMPORTING`, `VALIDATED`, `PUBLISHED`, and `FAILED` states.
2. Add optional bulk materialization for selected check diagnoses and CVE device details.
3. Add validation queries and publication gates for Checks, vulnerabilities, and paths.
4. Stream/batch Devices and Topology and validate counts before advancing a snapshot publication pointer.
5. Add multi-snapshot change examples and retention rules.

## Positioning guardrail

Use:

> The Forward Neo4j reference integration publishes selected, supported Forward API results as a snapshot-scoped
> property graph and correlates them with operator-supplied business context.

Do not claim that Forward exposes its internal graph, provides real-time graph streaming, or offers a native Neo4j or
GraphQL product surface.
