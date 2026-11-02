# AuraDB and remote Neo4j connector

AuraDB does not permit custom server plugins. The `connector` subproject is a client-side alternative that calls Forward
from a trusted execution host and writes to AuraDB with the Neo4j Java Driver. No `forward.*` procedures are installed
in AuraDB.

## Build and install

Release archives are named `forward-neo4j-connector-VERSION.tar` and `.zip`. To build locally:

```bash
./gradlew :connector:test :connector:distTar :connector:distZip
tar -xf connector/build/distributions/forward-neo4j-connector-0.4.0.tar
```

Use the generated launcher under `bin/forward-neo4j-connector`.

## Configuration

The connector uses the same `FORWARD_*` variables and protected `FORWARD_CREDENTIAL_FILE` as the plugin, plus:

| Variable | Purpose | Default |
|---|---|---|
| `NEO4J_URI` | AuraDB or remote Neo4j URI | required |
| `NEO4J_USERNAME` | Neo4j account | `neo4j` |
| `NEO4J_PASSWORD` | Neo4j password | unset |
| `NEO4J_PASSWORD_FILE` | Private file containing the Neo4j password | unset |
| `NEO4J_DATABASE` | Target database | `neo4j` |
| `FORWARD_NEO4J_BATCH_SIZE` | Driver write batch size, 1–50,000 | `5000` |

Off-loopback Neo4j URIs must use `neo4j+s://`, `neo4j+ssc://`, `bolt+s://`, or `bolt+ssc://`. AuraDB normally uses
`neo4j+s://`. Credential files must be regular files with no group or other permission bits.

```bash
chmod 0600 ~/.config/forward-neo4j/aura-password
export FORWARD_CREDENTIAL_FILE="$HOME/.config/forward-neo4j/forward.credentials"
export FORWARD_LICENSE_TIER=NS
export NEO4J_URI='neo4j+s://example.databases.neo4j.io'
export NEO4J_PASSWORD_FILE="$HOME/.config/forward-neo4j/aura-password"
forward-neo4j-connector --network NETWORK_ID --snapshot SNAPSHOT_ID
```

The default run stages and validates without publishing. Inspect the graph and rerun with `--publish` to atomically
advance `ForwardNetwork.currentSnapshotId`:

```bash
forward-neo4j-connector --network NETWORK_ID --snapshot SNAPSHOT_ID --publish
```

## Imported surface

The connector installs only the graph constraints it needs and imports:

- streamed Devices and physical Topology;
- all license-enabled bundled NQEs through asynchronous `nqe-executions`;
- Check summaries when Network or Security is selected; and
- vulnerability summaries and OS analysis when Security is selected.

Selected Check diagnosis, selected CVE device detail, curated path searches, and operator business context remain
explicit workflows in the self-managed surface. They are deliberately not bulk-crawled by the unattended AuraDB
connector. This preserves the bounded API contract and avoids inventing business or device correlation.

Each batch is independently committed and idempotent. A partial run remains staged; validation and `--publish` are the
visibility gate. The connector refuses to rewrite an already-published snapshot.
