# Forward-to-Neo4j engineering handoff

Status: `0.4.0` production release

Prepared: 2026-07-30; updated 2026-11-02 for 0.4.0

## Outcome

The repository now provides two supported deployment adapters over one snapshot-scoped graph contract:

- a Java 21 procedure plugin for self-managed Neo4j 2026.06.x; and
- a procedure-free Java Driver connector for AuraDB and remotely managed Neo4j.

Both use supported Forward read APIs and asynchronous `nqe-executions`. No tenant data, tenant identifiers,
credentials, or live-environment screenshots belong in this repository. Checked-in screenshots are generated from the
isolated synthetic dataset only.

## Implemented surface

Self-managed procedures include:

```cypher
CALL forward.plugin.info();
CALL forward.license.features();
CALL forward.testConnection();
CALL forward.schema.install();
CALL forward.inventory.begin("network-id", "snapshot-id");
CALL forward.inventory.devices.stream("network-id", "snapshot-id");
CALL forward.inventory.topology.stream("network-id", "snapshot-id");
CALL forward.inventory.complete("network-id", "snapshot-id", 0);
CALL forward.import.submit("interfaces-v1", "network-id", "snapshot-id");
CALL forward.import.begin("interfaces-v1", "network-id", "snapshot-id");
CALL forward.import.complete("interfaces-v1", "network-id", "snapshot-id", 100, 100);
CALL forward.import.fail("interfaces-v1", "network-id", "snapshot-id", "bounded error");
CALL forward.import.status("network-id", "snapshot-id");
CALL forward.snapshot.validate("network-id", "snapshot-id", ["interfaces-v1"]);
CALL forward.snapshot.publish("network-id", "snapshot-id");
CALL forward.snapshot.status("network-id", "snapshot-id");
CALL forward.snapshot.retention.preview("network-id", 2, 30);
CALL forward.snapshot.prune("network-id", ["explicit-old-snapshot"]);
CALL forward.metrics.prometheus("network-id");
CALL forward.nqe.projections();
CALL forward.nqe.stream("bgp-neighbors-v1", "network-id", "snapshot-id");
CALL forward.checks.stream("network-id", "snapshot-id");
CALL forward.checks.detail("check-id", "network-id", "snapshot-id");
CALL forward.vulnerabilities.stream("network-id", "snapshot-id");
CALL forward.vulnerabilities.detail("cve-id", "network-id", "snapshot-id");
CALL forward.paths.stream($flows, $options, "network-id", "snapshot-id");
```

The AuraDB connector imports streamed inventory/topology, every license-enabled bundled NQE, Check summaries, and
vulnerability summaries/OS analysis. It validates and stages by default; `--publish` advances the current pointer.
Selected details, curated paths, and business context remain explicit operator workflows rather than an unattended
bulk crawl.

## Production decisions

1. **Async NQE only.** Submit through `nqe-executions`, do not retry POST, poll at least five seconds on non-loopback
   Forward hosts, and lazily page the materialized result.
2. **Fixed extraction contracts.** Four NQEs ship as versioned resources with source SHA-256. Arbitrary NQE text is
   never accepted through Cypher or the connector CLI.
3. **Bounded base inventory.** Devices and Topology JSON arrays are incrementally parsed. Self-managed Cypher and the
   external connector write configurable batches; only device-name strings are retained for topology resolution.
4. **Fail-closed publication.** Deterministic component jobs and structural validation must pass before publication.
   Published snapshots are immutable through import surfaces.
5. **Explicit retention.** Preview always protects current, previous, and candidate pointers. Prune takes exact IDs,
   rechecks pointers in the write transaction, and deletes only integration-owned snapshot labels.
6. **No plugin background threads.** Scheduling uses a one-shot systemd unit and `flock`. The timer is shipped disabled.
   Prometheus textfiles are written atomically after a successful scheduled run.
7. **AuraDB separation.** Aura cannot load the plugin. The connector requires encrypted Bolt off loopback, accepts a
   private password file, and writes the same graph model over the Java Driver.
8. **License-aware selection.** `B`, `N`, `NP`, `S`, `NS`, and `NSP` gate API calls locally. The selector does not grant
   or discover entitlement; Forward remains authoritative.
9. **Business context is asserted.** Applications, ownership, criticality, device membership, and curated flows are
   operator-supplied. Check/CVE correlations use exact identities only.
10. **Recorded Predict snapshots are never published.** Automatic selection skips predicted snapshots and forks. An explicitly
    named predicted snapshot is imported with `kind = 'PREDICTED'`, linked `PREDICTED_FROM` its base, never becomes the
    candidate, and is refused by publication in both adapters. If Forward's snapshot list cannot be read when the
    snapshot is first named, its kind stays unknown and it is treated as collected (fail-open, documented).
11. **Synthetic public evidence.** Demo data, graph screenshots, and feature catalog screenshots contain invented names
    and documentation IP space only.

## Release gate

Run from a clean checkout with an isolated Gradle home if workstation init scripts alter build-cache configuration:

```bash
GRADLE_USER_HOME=/tmp/forward-neo4j-gradle-home \
  ./gradlew --max-workers=1 clean :test jar cyclonedxDirectBom \
    :connector:test :connector:distTar :connector:distZip :connector:cyclonedxDirectBom
jar tf build/libs/forward-neo4j-plugin-0.4.0.jar
docker build -f demo/Dockerfile -t forward-neo4j:verify .
```

Verify the plugin JAR contains all four NQE resources. Verify the connector archive contains its launcher and runtime
dependencies. Release both SBOMs, both connector archives, the plugin JAR, one `SHA256SUMS`, and attest every artifact.

For local deployment, verify Browser HTTP, Bolt, plugin version, license selector, and a real sanitized Forward
connection. The timer must remain disabled unless an operator has explicitly installed and tested its environment file.

## Intentional boundaries

- Check diagnosis and CVE device-detail materialization remain selected-ID operations.
- Path Search remains a curated list of approved flows, not a combinatorial crawl.
- Metrics contain network IDs as labels and therefore remain environment-sensitive local output.
- Neo4j is a derived analytical view. Forward is authoritative and this repository does not expose Forward's internal
  graph or a real-time graph-streaming contract.

## GitHub scope

Normal branch, pull-request, Actions, release, and Dependabot operations are in scope. Do not add branch protection,
rulesets, repository visibility changes, or other settings requiring GitHub organization/repository administration.
