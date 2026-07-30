# Synthetic demo

This directory contains two separate workflows:

1. `synthetic-data.cypher` creates a small graph using invented device names, documentation IP ranges, and no Forward
   credentials. It is safe for demos, screenshots, and query development.
2. The four NQE `import-*.cypher` scripts execute bundled queries against a configured Forward instance and import real results.
   Never use that graph for public screenshots or commit its output.
3. Checks, vulnerabilities, selected details, and paths use specialized Forward APIs.
   Availability follows `FORWARD_LICENSE_TIER`; see [the feature matrix](../docs/LICENSE_PROFILES.md).

## Run the synthetic graph

With the plugin installed:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j --file demo/synthetic-data.cypher
```

For public screenshots, use a fresh loopback-only Neo4j home and verify the isolation boundary before opening Browser:

```cypher
MATCH (node)
RETURN count(node) AS totalNodes,
       sum(CASE WHEN node.synthetic = true THEN 1 ELSE 0 END) AS syntheticNodes,
       collect(DISTINCT node.networkId) AS networkIds;
```

Proceed only when `totalNodes` equals `syntheticNodes` and `demo-network` is the sole network ID. Do not rely only on a
query filter when the same database also contains tenant imports.

Explore physical topology:

```cypher
MATCH path=(left:ForwardDevice)-[:HAS_INTERFACE]->(:ForwardInterface)
           -[:CONNECTED_TO]-(:ForwardInterface)<-[:HAS_INTERFACE]-(right:ForwardDevice)
WHERE left.networkId = "demo-network" AND left.snapshotId = "demo-snapshot"
RETURN path;
```

Explore the release screenshot's correlated topology, application, Check, and CVE view:

```cypher
MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
      -[:CONTAINS]->(device:ForwardDevice)
OPTIONAL MATCH topology=(device)-[:HAS_INTERFACE]->(:ForwardInterface)
                        -[:CONNECTED_TO]-(:ForwardInterface)<-[:HAS_INTERFACE]-(peer:ForwardDevice)
OPTIONAL MATCH (application:ForwardApplication {networkId: "demo-network", id: "sample-checkout"})
               -[placement:RUNS_ON]->(device)
OPTIONAL MATCH (vulnerability:ForwardVulnerability {networkId: "demo-network", id: "CVE-2099-0001"})
               -[risk:AFFECTS]->(device)
OPTIONAL MATCH (check:ForwardCheckObservation {
                 networkId: "demo-network", id: "synthetic-edge-redundancy"
               })-[impact:AFFECTS]->(device)
RETURN snapshot, device, topology, peer, application, placement, vulnerability, risk, check, impact;
```

Explore the selectable license profiles and their enabled imports:

```cypher
MATCH (profile:ForwardLicenseProfile {networkId: "demo-network"})
      -[selection:ENABLES]->(feature:ForwardIntegrationFeature)
RETURN profile, selection, feature;
```

The catalog includes all four bundled async projections. Short display captions keep the graph readable; each feature's
`id` contains its exact NQE name and its `source` is `nqe-executions`. Base, Network, Security, and combined profiles are
demo metadata, not a discovered or granted Forward entitlement.

Explore VRFs and BGP observations:

```cypher
MATCH path=(device:ForwardDevice)-[:HAS_NETWORK_INSTANCE]->(:ForwardNetworkInstance)
           -[:HAS_BGP_NEIGHBOR]->(:ForwardBgpNeighbor)
WHERE device.networkId = "demo-network" AND device.snapshotId = "demo-snapshot"
RETURN path;
```

The accompanying `business-context.json` and `application-flows.yaml` are synthetic inputs for implemented application
and Bulk Path Search correlation. `import-business-context.cypher` accepts an `applications` list; the path procedure
accepts a curated list of flow maps so callers choose exactly which application questions to evaluate.

For a live local import, use `run-import.sh` and the [customer demo runbook](../docs/CUSTOMER_DEMO.md). The wrapper records
component states, validates structure, and publishes only with an explicit `--publish` flag.

Once two snapshots have been published, `snapshot-diff.cypher` provides a read-only change demo for device membership,
BGP session state, Check state and violation counts, and newly observed CVEs.

## Container build

```bash
docker compose -f demo/compose.yaml up --build
```

The compose service is designed for local evaluation and uses the explicit environment values supplied by the caller.
Review port bindings and credential handling before using it on a shared host.
