# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Java 21, Gradle wrapper, Neo4j 2026.06.x. Two Gradle projects: root (the plugin) and `:connector`.

```bash
./gradlew clean test jar                      # plugin: tests + build/libs/forward-neo4j-plugin-<version>.jar
./gradlew :connector:test :connector:distTar :connector:distZip   # external connector
./gradlew cyclonedxDirectBom :connector:cyclonedxDirectBom        # SBOMs (plain `cyclonedxBom` is disabled on purpose)
./gradlew :test --tests 'com.forwardnetworks.neo4j.ForwardProceduresTest'            # single class
./gradlew :test --tests 'com.forwardnetworks.neo4j.ForwardProceduresTest.someMethod' # single test
```

CI (`.github/workflows/ci.yml`) runs the full set with `--no-daemon --max-workers=1`, then asserts the JAR contains the four
`nqe/*.nqe` resources, the connector dists contain `bin/forward-neo4j-connector`, and `bash -n` passes on `demo/` and `deploy/` scripts.
Tests use `neo4j-harness` (embedded Neo4j) with fake Forward HTTP servers; there is no live Forward dependency.
The version lives only in `build.gradle.kts`.

## Architecture

Two deployment adapters over **one snapshot-scoped property-graph contract** (see `docs/ARCHITECTURE.md`, `HANDOFF.md`):

- **Plugin** (`src/main`): Neo4j procedures under `forward.*` (`ForwardProcedures` is the entry point). Runs inside Neo4j, streams
  Forward data into batched Cypher imports. `compileOnly` Neo4j/Jackson, so the JAR is not fat.
- **Connector** (`connector/`): procedure-free CLI (`ForwardAuraConnector`, `ForwardExternalImporter`) using the Neo4j Java Driver
  for AuraDB/remote Neo4j. Depends on the root project, so it reuses `ForwardApiClient`, `ForwardConfig`, `NqeProjection`, etc.
  A graph/contract change generally has to be reflected in both the procedures and the external importer.

Key pieces in `src/main/java/com/forwardnetworks/neo4j/`: `ForwardApiClient` (read-only HTTP: incremental Devices/Topology array
parsing, async `nqe-executions` with ≥5s polling and lazy paging, no POST retries, bounded GET retries, never follows redirects with
auth), `ForwardConfig` (protected credential file: 4 lines — id, secret, host, default network), `ForwardLicenseTier` (B/N/NP/S/NS/NSP
profiles gate features locally before calling Forward), `NqeProjection` (fixed registry of bundled NQEs), `ImportJobManager`/
`ImportComponent` (per-component job state; a failed/missing required component blocks publish), `SnapshotImporter`,
`SnapshotRetentionManager`, `MetricsCollector`.

Rules baked into the design:
- All Forward-derived node identities are snapshot-scoped; checks, vulnerabilities, and paths are snapshot **observations**, not
  permanent edges. `ForwardNetwork.currentSnapshotId` only advances via explicit validate + publish.
- Imports are idempotent (stable identities, ≤5,000-row transactions); partial commits are acceptable, reruns converge.
- Credentials never pass through Cypher arguments. Do not expose arbitrary NQE through a procedure.
- Adding an NQE: immutable versioned file in `src/main/resources/nqe/`, registry entry in `NqeProjection`, fake async-execution
  and paging tests, documented identity/mapping, and a batched Cypher importer (`demo/import-*.cypher` holds the importer Cypher). Bump to `-v2`
  if meaning or key changes.
- Keep HTTP calls read-only. Scheduling is external (`deploy/` systemd one-shot + lock), never a plugin background thread.

## Conventions

- Fixtures, demos, docs, and screenshots must be synthetic: invented IDs and documentation IP ranges only (`192.0.2.0/24`,
  `198.51.100.0/24`, `203.0.113.0/24`). Never commit credentials, tenant/snapshot IDs, live responses, logs, or graph exports.
- GitHub Actions are pinned by commit SHA; keep that when editing workflows.
- `deploy/` holds the installer (checksum-verifying, atomic), systemd units, scheduler, health check and Prometheus exporter;
  `docs/` covers install, Aura, production, license profiles, NQE, and direct APIs.
