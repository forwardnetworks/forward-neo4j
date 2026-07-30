#!/usr/bin/env bash
set -euo pipefail

for command_name in awk cypher-shell install mktemp mv stat; do
  command -v "${command_name}" >/dev/null || {
    echo "required command is missing: ${command_name}" >&2
    exit 1
  }
done

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

neo4j_uri=${NEO4J_URI:-bolt://127.0.0.1:7687}
neo4j_username=${NEO4J_USERNAME:-neo4j}
network_id=${FORWARD_NETWORK_ID:-}
metrics_file=${FORWARD_NEO4J_METRICS_FILE:-${HOME}/.local/state/forward-neo4j/metrics/forward-neo4j.prom}
metrics_dir=$(dirname -- "${metrics_file}")
install -d -m 0750 "${metrics_dir}"
temporary_file=$(mktemp "${metrics_dir}/.forward-neo4j.prom.XXXXXX")
cleanup() {
  [[ ! -e ${temporary_file} ]] || rm -f -- "${temporary_file}"
}
trap cleanup EXIT

cypher-shell --non-interactive --format plain --wrap false \
  --address "${neo4j_uri}" --username "${neo4j_username}" \
  -P "{networkId: \"${network_id}\"}" \
  'CALL forward.metrics.prometheus($networkId) YIELD line RETURN line' \
  | awk '
      NR == 1 && $0 == "line" { next }
      /^".*"$/ {
        sub(/^"/, "")
        sub(/"$/, "")
        gsub(/""/, "\"")
      }
      { print }
    ' > "${temporary_file}"

[[ -s ${temporary_file} ]] || {
  echo "metrics export returned no data" >&2
  exit 1
}
chmod 0640 "${temporary_file}"
mv -- "${temporary_file}" "${metrics_file}"
echo "Wrote Forward Neo4j metrics atomically: ${metrics_file}"
