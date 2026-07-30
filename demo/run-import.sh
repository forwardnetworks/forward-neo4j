#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
network_id=""
snapshot_id=""
tier="B"
publish=false

usage() {
  echo "Usage: $0 [--network ID] [--snapshot ID] [--tier B|N|NP|S|NS|NSP] [--publish]" >&2
}

while (($# > 0)); do
  case "$1" in
    --network)
      network_id=${2:-}
      shift 2
      ;;
    --snapshot)
      snapshot_id=${2:-}
      shift 2
      ;;
    --tier)
      tier=${2:-}
      shift 2
      ;;
    --publish)
      publish=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

tier=${tier^^}
case "$tier" in
  B|N|NP|S|NS|NSP) ;;
  *)
    echo "Unsupported tier: $tier" >&2
    exit 2
    ;;
esac

if [[ -z ${NEO4J_PASSWORD:-} ]]; then
  echo "NEO4J_PASSWORD must be set in the environment" >&2
  exit 2
fi

for identifier in "$network_id" "$snapshot_id"; do
  if [[ -n $identifier && ! $identifier =~ ^[A-Za-z0-9._:-]+$ ]]; then
    echo "Network and snapshot IDs may contain only letters, numbers, dot, underscore, colon, and hyphen" >&2
    exit 2
  fi
done

neo4j_uri=${NEO4J_URI:-bolt://127.0.0.1:7687}
neo4j_username=${NEO4J_USERNAME:-neo4j}
cypher=(
  cypher-shell
  --non-interactive
  --format plain
  --wrap false
  --address "$neo4j_uri"
  --username "$neo4j_username"
)

base_params="{networkId: \"$network_id\", snapshotId: \"$snapshot_id\"}"
configured_tier=$("${cypher[@]}" \
  'CALL forward.plugin.info() YIELD configuredLicenseTier RETURN configuredLicenseTier' \
  | awk '{gsub(/"/, ""); if ($0 ~ /^(B|N|NP|S|NS|NSP)$/) value=$1} END {print value}')
if [[ $configured_tier != "$tier" ]]; then
  echo "Requested tier $tier does not match the plugin selector $configured_tier" >&2
  exit 2
fi

"${cypher[@]}" 'CALL forward.schema.install() YIELD constraintsInstalled RETURN constraintsInstalled'

inventory_output=$("${cypher[@]}" -P "$base_params" \
  'CALL forward.import.snapshot($networkId, $snapshotId)
   YIELD networkId, snapshotId
   RETURN "IMPORT_IDS=" + networkId + "|" + snapshotId AS marker')
printf '%s\n' "$inventory_output"
resolved=$(printf '%s\n' "$inventory_output" \
  | awk '/IMPORT_IDS=/ {value=$0; sub(/^"?IMPORT_IDS=/, "", value); sub(/".*$/, "", value); print value}' \
  | tail -n 1)
network_id=${resolved%%|*}
snapshot_id=${resolved#*|}
if [[ -z $network_id || -z $snapshot_id || $network_id == "$snapshot_id" ]]; then
  echo "Could not resolve imported network and snapshot IDs" >&2
  exit 1
fi
base_params="{networkId: \"$network_id\", snapshotId: \"$snapshot_id\"}"

components=()

mark_failed() {
  local component=$1
  "${cypher[@]}" -P "$base_params" -P "{component: \"$component\"}" \
    'CALL forward.import.fail($component, $networkId, $snapshotId, "cypher-shell importer failed")'
}

run_component() {
  local component=$1
  local script=$2
  local output
  local rows

  "${cypher[@]}" -P "$base_params" -P "{component: \"$component\"}" \
    'CALL forward.import.submit($component, $networkId, $snapshotId)
     YIELD component, state RETURN component, state'
  "${cypher[@]}" -P "$base_params" -P "{component: \"$component\"}" \
    'CALL forward.import.begin($component, $networkId, $snapshotId)
     YIELD component, state, attempt RETURN component, state, attempt'
  if ! output=$("${cypher[@]}" -P "$base_params" --file "$repo_dir/demo/$script"); then
    mark_failed "$component"
    return 1
  fi
  printf '%s\n' "$output"
  rows=$(printf '%s\n' "$output" | awk '/^[[:space:]]*[0-9]+[[:space:]]*$/ {value=$1} END {print value}')
  if [[ -z $rows ]]; then
    mark_failed "$component"
    echo "Importer $script did not return an importedRows value" >&2
    return 1
  fi
  "${cypher[@]}" -P "$base_params" -P "{component: \"$component\", rowsImported: $rows}" \
    'CALL forward.import.complete($component, $networkId, $snapshotId, $rowsImported, -1)
     YIELD component, state, rowsImported RETURN component, state, rowsImported'
  components+=("$component")
}

run_component interfaces-v1 import-interfaces.cypher
run_component interface-addresses-v1 import-interface-addresses.cypher

case "$tier" in
  N|NP|NS|NSP)
    run_component network-instances-v1 import-network-instances.cypher
    run_component bgp-neighbors-v1 import-bgp-neighbors.cypher
    ;;
esac

case "$tier" in
  N|NP|S|NS|NSP)
    run_component checks import-checks.cypher
    ;;
esac

case "$tier" in
  S|NS|NSP)
    run_component vulnerabilities import-vulnerabilities.cypher
    ;;
esac

component_list=$(printf '"%s",' "${components[@]}")
component_list="[${component_list%,}]"
"${cypher[@]}" -P "$base_params" -P "{requiredComponents: $component_list}" \
  'CALL forward.snapshot.validate($networkId, $snapshotId, $requiredComponents)
   YIELD state, requiredComponents, missingComponents, errors, devices,
         orphanInterfaces, orphanAddresses, orphanNetworkInstances, orphanBgpNeighbors, crossSnapshotLinks
   RETURN *'

if $publish; then
  "${cypher[@]}" -P "$base_params" \
    'CALL forward.snapshot.publish($networkId, $snapshotId)
     YIELD networkId, snapshotId, previousSnapshotId, publishedAt, components RETURN *'
else
  echo "Snapshot $snapshot_id is validated but not published; rerun with --publish after review."
fi
