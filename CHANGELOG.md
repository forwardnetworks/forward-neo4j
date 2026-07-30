# Changelog

## 0.2.2 - 2026-07-30

- Store plugin rollback copies with a non-`.jar` suffix so Neo4j cannot load the previous and active versions together.
- Migrate the legacy `forward-neo4j-plugin.previous.jar` name to a disabled archive and test that exactly one loadable
  plugin JAR remains after an upgrade.

## 0.2.1 - 2026-07-30

- Fix and smoke-test the published Docker build path; require an explicit Neo4j password and loopback-only Compose
  ports by default.
- Add atomic plugin installation, live local verification, reproducible JAR settings, Gradle distribution checksum,
  CycloneDX SBOM generation, release attestations, and published-release verification.
- Pin GitHub Actions, add dependency update and ownership metadata, and add the Forward MIT license.
- Emit an NQE provenance envelope for successful zero-row executions and update one projection marker per completed
  import instead of once per row.
- Enforce five-second-or-longer polling for non-loopback Forward URLs and require the timeout to exceed the interval.
- Add read-only current-versus-previous snapshot demo queries.

## 0.2.0 - 2026-07-30

- Add snapshot-scoped Device and Topology import procedures.
- Add fixed `interfaces-v1`, `interface-addresses-v1`, `network-instances-v1`, and `bgp-neighbors-v1` projections.
- Execute NQEs asynchronously through `nqe-executions`, with bounded polling and lazy result paging.
- Add batched, idempotent Cypher graph importers and twenty uniqueness constraints.
- Add protected credential-file support and a sanitized live connectivity procedure.
- Add selectable Forward tier profiles with projection- and API-level feature gates.
- Add read-only Checks summary/detail, Vulnerability summary/detail, and curated Bulk Path Search procedures.
- Add batched graph importers for check, CVE/OS, and ordered path/hop observations.
- Add component import jobs, structural validation, immutable publication, and current-snapshot gating.
- Add set-oriented inventory writes, ordered `NEXT_HOP` relationships, and selected Check/CVE detail importers.
- Add operator-supplied application, owner, device, and flow correlation plus a customer demo runbook.
- Add Arch/user-service, Docker, LAN-firewall, TLS, architecture, security, and synthetic-demo documentation.
- Add synthetic-only screenshots and CI release artifact validation.
