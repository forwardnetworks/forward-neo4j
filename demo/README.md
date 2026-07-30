# Synthetic demo

This directory contains two separate workflows:

1. `synthetic-data.cypher` creates a small graph using invented device names, documentation IP ranges, and no Forward
   credentials. It is safe for demos, screenshots, and query development.
2. The four `import-*.cypher` scripts execute bundled NQEs against a configured Forward instance and import real results.
   Never use that graph for public screenshots or commit its output.
3. `import-checks.cypher`, `import-vulnerabilities.cypher`, and `import-paths.cypher` use specialized Forward APIs.
   Availability follows `FORWARD_LICENSE_TIER`; see [the feature matrix](../docs/LICENSE_PROFILES.md).

## Run the synthetic graph

With the plugin installed:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j --file demo/synthetic-data.cypher
```

Explore physical topology:

```cypher
MATCH path=(left:ForwardDevice)-[:HAS_INTERFACE]->(:ForwardInterface)
           -[:CONNECTED_TO]-(:ForwardInterface)<-[:HAS_INTERFACE]-(right:ForwardDevice)
WHERE left.networkId = "demo-network" AND left.snapshotId = "demo-snapshot"
RETURN path;
```

Explore VRFs and BGP observations:

```cypher
MATCH path=(device:ForwardDevice)-[:HAS_NETWORK_INSTANCE]->(:ForwardNetworkInstance)
           -[:HAS_BGP_NEIGHBOR]->(:ForwardBgpNeighbor)
WHERE device.networkId = "demo-network" AND device.snapshotId = "demo-snapshot"
RETURN path;
```

The accompanying `business-context.json` and `application-flows.yaml` are synthetic inputs for future correlation and
Bulk Path Search examples. The path procedure takes a Cypher list of flow maps so callers choose exactly which
application questions to evaluate.

## Container build

```bash
docker compose -f demo/compose.yaml up --build
```

The compose service is designed for local evaluation and uses the explicit environment values supplied by the caller.
Review port bindings and credential handling before using it on a shared host.
