# Checks, vulnerabilities, and paths

The integration keeps specialized Forward result types on their authoritative APIs. These procedures do not replace
Checks, vulnerability analysis, or Path Search with NQE.

## Checks

Required facet: Network or Security.

```cypher
CALL forward.checks.stream("network-id", "snapshot-id")
YIELD networkId, snapshotId, row
RETURN row.id AS id, row.name AS name, row.definition.checkType AS type,
       row.status AS status, row.priority AS priority, row.numViolations AS violations
ORDER BY status, name;

CALL forward.checks.detail("check-id", "network-id", "snapshot-id")
YIELD row
RETURN row;
```

Import status observations in bounded transactions:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  --param 'networkId => ""' --param 'snapshotId => ""' \
  --file demo/import-checks.cypher
```

The graph stores check identity, type, status, priority, violation count, selected timestamps, and tags. Diagnosis stays
on-demand by default because it can be large. `demo/import-check-details.cypher` materializes only operator-selected
Check IDs as `ForwardCheckDiagnosisDetail` nodes and links exact reference-key matches to existing devices.

## Vulnerability analysis

Required facet: Security.

```cypher
CALL forward.vulnerabilities.stream("network-id", "snapshot-id")
YIELD snapshotId, indexCreatedAt, row
RETURN row.id AS cve, row.hasCisaKevEntry AS knownExploited,
       [item IN row.osInfos | {vendor: item.vendor, os: item.os, severity: item.severity,
                              devices: item.deviceCount}] AS affectedOs
ORDER BY cve;

CALL forward.vulnerabilities.detail("CVE-2099-0001", "network-id", "snapshot-id")
YIELD row
RETURN row;
```

Import summary nodes and per-OS count observations:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  --param 'networkId => ""' --param 'snapshotId => ""' \
  --file demo/import-vulnerabilities.cypher
```

`ForwardVulnerability` is keyed by CVE within a network snapshot. `ForwardVulnerabilityOs` preserves vendor, OS,
severity, CVSS scores, affected-version lists, and detection-result counts. The summary importer does not create an
affected-device edge without the device-level detail response. `demo/import-vulnerability-details.cypher` accepts an
explicit CVE list and creates `AFFECTS` relationships only for exact device names already present in the snapshot.

## Curated Bulk Path Search

Required facet: Network or Security.

Path Search accepts an explicit catalog of named flows. It does not enumerate arbitrary address/port combinations.
Each flow requires `name`, `dstIp`, and either `srcIp` or `from`. Supported packet fields are `from`, `srcIp`, `dstIp`,
`ipProto`, `srcPort`, `dstPort`, `icmpType`, TCP flags, `appId`, `userId`, `userGroupId`, `url`, and `domain`.

```cypher
WITH [
  {name: "sample-checkout-to-database", srcIp: "192.0.2.10", dstIp: "198.51.100.20",
   ipProto: 6, dstPort: "5432"},
  {name: "edge-to-dns", from: "edge-a", dstIp: "203.0.113.53", ipProto: 17, dstPort: "53"}
] AS flows
CALL forward.paths.stream(
  flows,
  {intent: "PREFER_DELIVERED", maxCandidates: 100, maxResults: 3, maxReturnPathResults: 1,
   maxSeconds: 30, maxOverallSeconds: 120, includeTags: true, includeNetworkFunctions: false},
  "network-id",
  "snapshot-id"
)
YIELD index, name, query, result
RETURN index, name, result.timedOut AS timedOut, result.info.paths AS forwardPaths,
       result.returnPathInfo.paths AS returnPaths
ORDER BY index;
```

The local guardrail accepts at most 1,000 uniquely named flows per request, matching Forward's recommended bulk size.
It strips `name` before sending the ordered query array and reattaches names to responses by index. Unknown query or
request-option fields fail locally. The POST performs a read-only path computation and is not automatically retried.

For a graph import, pass `flows` and `pathOptions` to the script:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  --param 'networkId => ""' \
  --param 'snapshotId => ""' \
  --param 'flows => [{name: "sample-checkout-to-database", srcIp: "192.0.2.10", dstIp: "198.51.100.20", ipProto: 6, dstPort: "5432"}]' \
  --param 'pathOptions => {maxResults: 3, maxReturnPathResults: 1, includeTags: true}' \
  --file demo/import-paths.cypher
```

`ForwardPathObservation` stores the query and overall response. `ForwardPathResult` preserves forward/return direction
and outcomes. Ordered `HAS_HOP {index}` relationships point to `ForwardPathHop` nodes, which link to existing devices
only when Forward supplies a device name that matches inventory. Adjacent hops also receive a `NEXT_HOP` relationship;
this is an ordered path observation, not a physical-topology edge.

## Read-only Forward boundary

The integration calls only:

- `GET /api/snapshots/{snapshotId}/checks`
- `GET /api/snapshots/{snapshotId}/checks/{checkId}`
- `GET /api/networks/{networkId}/vulnerabilities?v=2&snapshotId=...`
- `GET /api/networks/{networkId}/vulnerabilities/{cveId}?snapshotId=...`
- `POST /api/networks/{networkId}/paths-bulk?snapshotId=...` for computation only

It never creates, edits, deactivates, or reprocesses a Forward check, never changes vulnerability analysis, and never
triggers snapshot processing.
