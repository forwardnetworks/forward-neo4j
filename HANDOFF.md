# Forward-to-Neo4j engineering handoff

Status: `0.2.0` reference integration prepared for initial release

Prepared: 2026-07-30

## Outcome

The repository contains a Java 21 Neo4j plugin for publishing selected Forward Networks API results into a
snapshot-scoped property graph. It uses supported read-only data APIs and Forward's asynchronous NQE execution surface.
No tenant data, tenant identifiers, credentials, or live-environment screenshots belong in this repository.
The checked-in screenshots were captured from a second loopback-only Neo4j home containing 58 synthetic nodes and no
other network ID.

## Implemented procedure surface

```cypher
CALL forward.plugin.info();
CALL forward.license.features();
CALL forward.testConnection();
CALL forward.schema.install();
CALL forward.import.snapshot("network-id", "snapshot-id");
CALL forward.import.submit("interfaces-v1", "network-id", "snapshot-id");
CALL forward.import.begin("interfaces-v1", "network-id", "snapshot-id");
CALL forward.import.complete("interfaces-v1", "network-id", "snapshot-id", 100, 100);
CALL forward.import.fail("interfaces-v1", "network-id", "snapshot-id", "bounded error");
CALL forward.import.status("network-id", "snapshot-id");
CALL forward.snapshot.validate("network-id", "snapshot-id", ["interfaces-v1"]);
CALL forward.snapshot.publish("network-id", "snapshot-id");
CALL forward.snapshot.status("network-id", "snapshot-id");
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
9. **Publication gate.** Imports stage a candidate snapshot. Component jobs move through `SUBMITTED`, `IMPORTING`,
   `VALIDATED`, or `FAILED`; structural validation must pass before an explicit atomic publication updates
   `ForwardNetwork.currentSnapshotId`.
10. **Business context is asserted, not inferred.** Applications, owners, criticality, device membership, and flow
    definitions are operator-supplied. Selected Check and CVE detail uses exact Forward identities only.
11. **Screenshot data is reproducible.** `demo/synthetic-data.cypher` includes the public correlated graph plus four
    synthetic license-profile nodes and eight integration-feature nodes. Their 24 `ENABLES` relationships document the
    selector without querying or representing a tenant entitlement.

## Runtime and security behavior

- `FORWARD_CREDENTIAL_FILE` must be a regular POSIX file with no group/other permissions.
- Non-loopback Forward URLs require HTTPS, and HTTP redirects are disabled.
- GET retries are limited to transient 429/502/503/504 responses and I/O failures.
- Cancellation from Neo4j is checked during NQE polling and page consumption.
- Connection tests return no network IDs or names.
- The deployable service is localhost-only by default. LAN access is an explicit connector plus firewall configuration.
- The Arch installation path includes a checksum-pinned native Neo4j Browser installer for distributions that omit the
  `NEO4J_HOME/web` archive.

## Verification baseline

The harness suite covers procedure registration, fixed projection enumeration, protected credential files,
asynchronous submit/status handling, and lazy multi-page result retrieval. The release gate is:

```bash
./gradlew clean test jar
jar tf build/libs/forward-neo4j-plugin-0.2.0.jar
```

The JAR must contain all four `.nqe` resources. Release screenshots must be captured from `demo/synthetic-data.cypher`
in an isolated Neo4j home, never from an existing graph. The current capture gate returned 58 total nodes, 58 synthetic
nodes, and only `demo-network` before the temporary instance was stopped.

## Known gaps

1. `forward.import.snapshot()` uses set-oriented writes but still downloads and writes base inventory/topology in one
   Neo4j transaction.
2. `ForwardProjectionRun` markers are updated per NQE row; component jobs avoid ambiguity, but the lock traffic remains.
3. A zero-row NQE has a validated component job but no row-derived `ForwardProjectionRun` provenance node.
4. Snapshot retention and pruning policy are not implemented.
5. Check and vulnerability detail materialization is intentionally selected-ID only, not an unbounded bulk crawl.
6. AuraDB requires an external connector rather than this self-managed plugin.

## Next build order

1. Add a streamed/batched Devices and Topology surface for very large estates.
2. Move NQE projection provenance updates out of the per-row importer transaction.
3. Add multi-snapshot change examples, retention rules, and safe pruning procedures.
4. Add a scheduler/metrics surface for unattended operation after the import contract stabilizes.
5. Build an external connector for AuraDB.

## Positioning guardrail

Use:

> The Forward Neo4j reference integration publishes selected, supported Forward API results as a snapshot-scoped
> property graph and correlates them with operator-supplied business context.

Do not claim that Forward exposes its internal graph, provides real-time graph streaming, or offers a native Neo4j or
GraphQL product surface.
