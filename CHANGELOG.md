# Changelog

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
