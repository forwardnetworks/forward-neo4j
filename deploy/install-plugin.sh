#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 PLUGIN_JAR [NEO4J_HOME]" >&2
}

if (($# < 1 || $# > 2)); then
  usage
  exit 2
fi

plugin_source_input=$1
neo4j_home_input=${2:-${NEO4J_HOME:-${HOME}/.local/state/forward-neo4j}}

for command_name in awk grep install jar mktemp mv realpath sha256sum; do
  command -v "${command_name}" >/dev/null || {
    echo "required command is missing: ${command_name}" >&2
    exit 1
  }
done

plugin_source=$(realpath -- "${plugin_source_input}")
[[ -f ${plugin_source} ]] || {
  echo "plugin JAR is not a regular file: ${plugin_source}" >&2
  exit 1
}

plugin_directory=$(dirname -- "${plugin_source}")
checksum_file="${plugin_source}.sha256"
if [[ -f ${checksum_file} ]]; then
  (
    cd -- "${plugin_directory}"
    sha256sum --check --status "$(basename -- "${checksum_file}")"
  ) || {
    echo "plugin checksum validation failed" >&2
    exit 1
  }
elif [[ -f ${plugin_directory}/SHA256SUMS ]]; then
  checksum_file="${plugin_directory}/SHA256SUMS"
  plugin_name=$(basename -- "${plugin_source}")
  expected_sha256=$(awk -v plugin_name="${plugin_name}" '$2 == plugin_name {print $1}' "${checksum_file}")
  [[ ${expected_sha256} =~ ^[0-9a-fA-F]{64}$ ]] || {
    echo "SHA256SUMS does not contain the plugin JAR" >&2
    exit 1
  }
  printf '%s  %s\n' "${expected_sha256}" "${plugin_source}" | sha256sum --check --status || {
    echo "plugin checksum validation failed" >&2
    exit 1
  }
fi

required_entries=(
  com/forwardnetworks/neo4j/ForwardProcedures.class
  nqe/interfaces-v1.nqe
  nqe/interface-addresses-v1.nqe
  nqe/network-instances-v1.nqe
  nqe/bgp-neighbors-v1.nqe
)
for entry in "${required_entries[@]}"; do
  jar --list --file "${plugin_source}" | grep --fixed-strings --line-regexp --quiet "${entry}" || {
    echo "plugin JAR is missing required entry: ${entry}" >&2
    exit 1
  }
done

neo4j_home=$(realpath -m -- "${neo4j_home_input}")
case "${neo4j_home}" in
  ""|/|/etc|/home|/opt|/tmp|/usr|/var|/var/lib|"${HOME}")
    echo "refusing unsafe Neo4j home: ${neo4j_home}" >&2
    exit 1
    ;;
esac

plugin_dir="${neo4j_home}/plugins"
plugin_target="${plugin_dir}/forward-neo4j-plugin.jar"
previous_target="${plugin_dir}/forward-neo4j-plugin.previous.jar"
install -d -m 0755 "${plugin_dir}"
staged_target=$(mktemp "${plugin_dir}/.forward-neo4j-plugin.XXXXXX")
cleanup() {
  [[ ! -e ${staged_target} ]] || rm -f -- "${staged_target}"
}
trap cleanup EXIT

install -m 0644 "${plugin_source}" "${staged_target}"
if [[ -f ${plugin_target} ]]; then
  install -m 0644 "${plugin_target}" "${previous_target}"
fi
mv -- "${staged_target}" "${plugin_target}"

echo "Installed Forward Neo4j plugin atomically: ${plugin_target}"
if [[ -f ${previous_target} ]]; then
  echo "Previous plugin retained for rollback: ${previous_target}"
fi
echo "Restart Neo4j, then run deploy/verify-install.sh."
