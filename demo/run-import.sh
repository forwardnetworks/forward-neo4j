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

if [[ -z ${NEO4J_PASSWORD:-} && -n ${NEO4J_PASSWORD_FILE:-} ]]; then
  [[ -f ${NEO4J_PASSWORD_FILE} ]] || {
    echo "NEO4J_PASSWORD_FILE is not a regular file" >&2
    exit 2
  }
  password_mode=$(stat -c '%a' "${NEO4J_PASSWORD_FILE}")
  if (( (8#${password_mode} & 077) != 0 )); then
    echo "NEO4J_PASSWORD_FILE must not grant group or other permissions" >&2
    exit 2
  fi
  IFS= read -r NEO4J_PASSWORD < "${NEO4J_PASSWORD_FILE}" || true
  export NEO4J_PASSWORD
fi
if [[ -z ${NEO4J_PASSWORD:-} ]]; then
  echo "Set NEO4J_PASSWORD or NEO4J_PASSWORD_FILE" >&2
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
  'CALL forward.inventory.begin($networkId, $snapshotId)
   YIELD networkId, snapshotId, alreadyPublished
   RETURN "IMPORT_IDS=" + networkId + "|" + snapshotId + "|" + toString(alreadyPublished) AS marker')
printf '%s\n' "$inventory_output"
resolved=$(printf '%s\n' "$inventory_output" \
  | awk '/IMPORT_IDS=/ {value=$0; sub(/^"?IMPORT_IDS=/, "", value); sub(/".*$/, "", value); print value}' \
  | tail -n 1)
network_id=${resolved%%|*}
resolved_remainder=${resolved#*|}
snapshot_id=${resolved_remainder%%|*}
already_published=${resolved_remainder#*|}
if [[ -z $network_id || -z $snapshot_id || $network_id == "$snapshot_id" ]]; then
  echo "Could not resolve imported network and snapshot IDs" >&2
  exit 1
fi
if [[ $already_published == true ]]; then
  echo "Snapshot $snapshot_id is already the published current snapshot; no import is needed."
  exit 0
fi
base_params="{networkId: \"$network_id\", snapshotId: \"$snapshot_id\"}"

fail_inventory() {
  local error=$1
  "${cypher[@]}" -P "$base_params" -P "{error: \"$error\"}" \
    'CALL forward.import.fail("inventory", $networkId, $snapshotId, $error)' >/dev/null || true
}

if ! device_output=$("${cypher[@]}" -P "$base_params" --file "$repo_dir/demo/import-inventory-devices.cypher"); then
  fail_inventory "streamed device importer failed"
  exit 1
fi
printf '%s\n' "$device_output"
device_count=$(printf '%s\n' "$device_output" \
  | awk '/INVENTORY_DEVICES=/ {value=$0; sub(/^.*INVENTORY_DEVICES=/, "", value); sub(/[^0-9].*$/, "", value); print value}' \
  | tail -n 1)
if [[ -z $device_count || $device_count == 0 ]]; then
  fail_inventory "streamed device import produced zero rows"
  echo "Streamed inventory returned no devices; snapshot will not be validated" >&2
  exit 1
fi

if ! topology_output=$("${cypher[@]}" -P "$base_params" --file "$repo_dir/demo/import-inventory-topology.cypher"); then
  fail_inventory "streamed topology importer failed"
  exit 1
fi
printf '%s\n' "$topology_output"
topology_counts=$(printf '%s\n' "$topology_output" \
  | awk '/INVENTORY_TOPOLOGY=/ {value=$0; sub(/^.*INVENTORY_TOPOLOGY=/, "", value); sub(/[^0-9|].*$/, "", value); print value}' \
  | tail -n 1)
if [[ -z $topology_counts || $topology_counts != *"|"* ]]; then
  fail_inventory "streamed topology import did not return counts"
  echo "Could not resolve streamed topology counts" >&2
  exit 1
fi
skipped_links=${topology_counts#*|}
if ! "${cypher[@]}" -P "$base_params" -P "{skippedLinks: $skipped_links}" \
  'CALL forward.inventory.complete($networkId, $snapshotId, $skippedLinks)
   YIELD devices, links, skippedLinks RETURN devices, links, skippedLinks'; then
  fail_inventory "streamed inventory validation failed"
  exit 1
fi

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
