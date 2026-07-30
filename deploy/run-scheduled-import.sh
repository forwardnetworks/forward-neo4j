#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
for command_name in flock; do
  command -v "${command_name}" >/dev/null || {
    echo "required command is missing: ${command_name}" >&2
    exit 1
  }
done

runtime_dir=${XDG_RUNTIME_DIR:-/tmp}
lock_file=${FORWARD_NEO4J_LOCK_FILE:-${runtime_dir}/forward-neo4j-import.lock}
exec 9> "${lock_file}"
if ! flock --nonblock 9; then
  echo "Another Forward Neo4j import owns ${lock_file}; skipping this timer run"
  exit 0
fi

tier=${FORWARD_LICENSE_TIER:-B}
network_id=${FORWARD_NETWORK_ID:-}
arguments=(--tier "${tier}" --publish)
if [[ -n ${network_id} ]]; then
  arguments+=(--network "${network_id}")
fi
"${repo_dir}/demo/run-import.sh" "${arguments[@]}"
"${repo_dir}/deploy/export-metrics.sh"
