#!/usr/bin/env bash
set -euo pipefail

browser_version="2025.8.0"
browser_release_date="2025.08.07"
browser_sha256="4ed8ca29a27519a79f7013258acbcb020649a7fef354e1e157c1dc8f0a83cef2"
browser_url="https://github.com/neo4j/neo4j-browser/releases/download/${browser_version}/neo4j-browser-${browser_version}.tgz"

neo4j_home_input="${1:-${NEO4J_HOME:-${HOME}/.local/state/forward-neo4j}}"

for command_name in curl grep jar realpath sha256sum tar; do
  command -v "${command_name}" >/dev/null || {
    echo "required command is missing: ${command_name}" >&2
    exit 1
  }
done

neo4j_home="$(realpath -m -- "${neo4j_home_input}")"
case "${neo4j_home}" in
  ""|/|/etc|/home|/opt|/tmp|/usr|/var|/var/lib|"${HOME}")
    echo "refusing unsafe Neo4j home: ${neo4j_home}" >&2
    exit 1
    ;;
esac

temporary_dir="$(mktemp -d -p /tmp forward-neo4j-browser.XXXXXX)"
cleanup() {
  if [[ "${temporary_dir}" == /tmp/forward-neo4j-browser.* ]]; then
    rm -rf -- "${temporary_dir}"
  fi
}
trap cleanup EXIT

release_archive="${temporary_dir}/neo4j-browser-${browser_version}.tgz"
curl --fail --location --retry 3 --output "${release_archive}" "${browser_url}"
printf '%s  %s\n' "${browser_sha256}" "${release_archive}" | sha256sum --check --status

tar --extract --gzip --file "${release_archive}" --directory "${temporary_dir}" package/dist
[[ -f "${temporary_dir}/package/dist/index.html" ]] || {
  echo "Neo4j Browser release is missing dist/index.html" >&2
  exit 1
}

install -d -m 0755 "${temporary_dir}/neo4j-browser"
cp -a "${temporary_dir}/package/dist/." "${temporary_dir}/neo4j-browser/"

native_archive="${temporary_dir}/neo4j-browser-${browser_release_date}+0.zip"
jar --create --no-manifest --file "${native_archive}" \
  -C "${temporary_dir}" neo4j-browser
jar --list --file "${native_archive}" | grep --fixed-strings --line-regexp --quiet \
  'neo4j-browser/index.html'

web_dir="${neo4j_home}/web"
target_archive="${web_dir}/neo4j-browser-${browser_release_date}+0.zip"
install -d -m 0755 "${web_dir}"
install -m 0644 "${native_archive}" "${target_archive}"

echo "Installed Neo4j Browser ${browser_version} into Neo4j's native web module:"
echo "  ${target_archive}"
echo "Restart Neo4j, then verify /browser/ returns HTTP 200."
