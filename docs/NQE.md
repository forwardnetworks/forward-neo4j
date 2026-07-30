# Bundled NQE contracts

NQE is used here for modeled, vendor-neutral structural facts that are more expressive than basic inventory endpoints.
Every query is a repository resource, packaged into the release JAR, and exposed by a fixed name.

## Sources and identities

| Name | Facet | Source | Output | Stable graph identity |
|---|---|---|---|---|
| `interfaces-v1` | Base-compatible | [source](../src/main/resources/nqe/interfaces-v1.nqe) | interface type, layer, MTU, state, speed, aggregation | network + snapshot + device + interface |
| `interface-addresses-v1` | Base-compatible | [source](../src/main/resources/nqe/interface-addresses-v1.nqe) | IPv4/IPv6 assignments from subinterface, routed VLAN, bridge, and tunnel containers | network + snapshot + device + interface + source kind + IP/prefix |
| `network-instances-v1` | Network | [source](../src/main/resources/nqe/network-instances-v1.nqe) | VRF/type plus interface, VLAN, protocol, and route counts | network + snapshot + device + instance name |
| `bgp-neighbors-v1` | Network | [source](../src/main/resources/nqe/bgp-neighbors-v1.nqe) | local/peer AS, state, duration, prefix counters, optional resolved peer identity | network + snapshot + device + instance + neighbor address |

Call the catalog to obtain each embedded source hash:

```cypher
CALL forward.nqe.projections()
YIELD name, version, sha256, enabled, requiredFacet
RETURN name, version, sha256, enabled, requiredFacet
ORDER BY name;
```

The procedure does not accept NQE text. Adding a contract requires a reviewed resource, a registry entry in
`NqeProjection`, tests, and an import mapping. If output meaning or key fields change, add a new `-v2` name.

## Async execution lifecycle

`forward.nqe.stream()` uses only the async Forward NQE execution endpoints:

```text
POST /api/networks/{networkId}/nqe-executions?snapshotId={snapshotId}
  -> executionKey
GET  /api/networks/{networkId}/nqe-executions/{executionKey}
  -> poll until COMPLETED and outcome OK
GET  /api/networks/{networkId}/nqe-executions/{executionKey}/result
  -> materialized pages, consumed lazily
```

Polling never occurs more often than every five seconds. The call has a configurable total timeout, checks Neo4j
cancellation between waits/pages, and holds at most one JSON result page in memory. GETs have bounded transient retries;
the submission POST does not retry because its response could be lost after Forward already created the execution.

## Preview a projection

```cypher
CALL forward.nqe.stream("interfaces-v1", "network-id", "snapshot-id")
YIELD snapshotId, projection, projectionVersion, projectionSha256, executionKey, row
RETURN snapshotId, projection, projectionVersion, projectionSha256, row
LIMIT 10;
```

Use empty IDs to select the credential file's default network and newest already-processed snapshot:

```cypher
CALL forward.nqe.stream("network-instances-v1", "", "")
YIELD row
RETURN row
LIMIT 10;
```

## Full graph imports

Each script streams a fixed NQE and performs idempotent `MERGE` operations in 5,000-row transactions:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  --param 'networkId => ""' --param 'snapshotId => ""' \
  --file demo/import-interfaces.cypher

cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  --param 'networkId => ""' --param 'snapshotId => ""' \
  --file demo/import-interface-addresses.cypher

cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  --param 'networkId => ""' --param 'snapshotId => ""' \
  --file demo/import-network-instances.cypher

cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  --param 'networkId => ""' --param 'snapshotId => ""' \
  --file demo/import-bgp-neighbors.cypher
```

In Neo4j Browser, prepend `:auto` when pasting an import script because `CALL { ... } IN TRANSACTIONS` must execute as
an implicit transaction.

## Provenance and validation

Each imported row merges a `ForwardProjectionRun` with:

- network and snapshot IDs;
- projection name and semantic version;
- SHA-256 of the exact NQE source;
- last Forward execution key and import timestamp.

Batch commits are restartable, not globally atomic. After an interruption, rerun the same projection and validate the
expected node/relationship counts plus projection hash before treating the snapshot as published. A future job layer
will make `SUBMITTED`, `IMPORTING`, `VALIDATED`, `PUBLISHED`, and `FAILED` explicit.
