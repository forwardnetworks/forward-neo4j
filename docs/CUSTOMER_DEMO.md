# Customer demo runbook

This runbook is designed for a live customer tenant without putting customer data in this repository. All live data
stays in the operator's local Neo4j database. Public screenshots continue to use `demo/synthetic-data.cypher` only.

## 1. Confirm scope

Choose one processed snapshot and the actual Forward tier before importing. Do not claim unavailable facets:

- `B`: inventory, physical topology, interfaces, and interface addresses;
- `N`/`NP`: Base plus network instances, BGP, Checks, and optional Paths;
- `S`: Base-compatible interface projections, Checks, vulnerabilities, and optional Paths; or
- `NS`/`NSP`: every current integration component.

The Predict facet does not add a separate graph component in this release.

## 2. Stage and inspect

Set Neo4j connection environment locally, then run without `--publish` first:

```bash
export NEO4J_URI=bolt://127.0.0.1:7687
export NEO4J_USERNAME=neo4j
read -rsp "Neo4j password: " NEO4J_PASSWORD
export NEO4J_PASSWORD

demo/run-import.sh --tier NS --snapshot SNAPSHOT_ID
```

Review job and structural state:

```cypher
CALL forward.import.status("NETWORK_ID", "SNAPSHOT_ID");
CALL forward.snapshot.status("NETWORK_ID", "SNAPSHOT_ID");
```

Only publish after the expected components are `VALIDATED` and validation errors are empty.

## 3. Optional customer context

Application ownership, criticality, device membership, and flow definitions are operator-supplied facts. Import them
only when the customer confirms them; the integration does not infer business meaning.

Use `demo/import-business-context.cypher` with an `applications` list shaped like the synthetic
`demo/business-context.json`. Device names must match the selected snapshot. Flow names should match the curated path
catalog so `ForwardApplicationFlow` can connect to `ForwardPathObservation` through `EVALUATED_AS`.

## 4. Optional high-value details

The summary imports are safe defaults. For a focused demo, select a small customer-approved set:

- failing Check IDs with `demo/import-check-details.cypher`;
- relevant CVE IDs with `demo/import-vulnerability-details.cypher`; and
- approved application flows with `demo/import-paths.cypher`.

Selected vulnerability detail creates `AFFECTS` relationships only when Forward supplies an exact device name that
already exists in the snapshot. Check diagnosis references link to a device only on an exact key match. No fuzzy or
address-only inference is used.

## 5. Demo sequence

Run [`demo/customer-demo.cypher`](../demo/customer-demo.cypher) section by section:

1. publication and coverage summary;
2. physical topology;
3. network-instance and BGP health;
4. failing Checks;
5. vulnerability-to-device impact;
6. application-to-device risk correlation; and
7. ordered path hops and outcomes.

After a second snapshot has been validated and published, run
[`demo/snapshot-diff.cypher`](../demo/snapshot-diff.cypher) to show added and removed devices, BGP state transitions,
changed Checks, and newly observed CVEs. Every comparison resolves through `currentSnapshotId` and
`previousSnapshotId`; it never compares a staged or invalid snapshot.

Lead with the operational question, not the graph size. A strong example is: “For this published Forward snapshot,
show the critical application, the devices it runs on, failed verification or CVE observations affecting those
devices, and the modeled path outcome for an approved flow.”

## 6. Cleanup and disclosure

Unset `NEO4J_PASSWORD` after the session. Treat the local graph, Browser history, terminal output, and Neo4j logs as
customer data. Do not reuse live screenshots in the public repository or release.
