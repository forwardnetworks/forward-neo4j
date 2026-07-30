#!/usr/bin/env bash
set -euo pipefail

verify_forward=false
case "${1:-}" in
  "") ;;
  --forward) verify_forward=true ;;
  *)
    echo "Usage: $0 [--forward]" >&2
    exit 2
    ;;
esac

if [[ -z ${NEO4J_PASSWORD:-} ]]; then
  echo "NEO4J_PASSWORD must be set in the environment" >&2
  exit 2
fi

for command_name in curl cypher-shell; do
  command -v "${command_name}" >/dev/null || {
    echo "required command is missing: ${command_name}" >&2
    exit 1
  }
done

neo4j_uri=${NEO4J_URI:-bolt://127.0.0.1:7687}
neo4j_http_url=${NEO4J_HTTP_URL:-http://127.0.0.1:7474}
neo4j_username=${NEO4J_USERNAME:-neo4j}
cypher=(
  cypher-shell
  --non-interactive
  --format plain
  --wrap false
  --address "${neo4j_uri}"
  --username "${neo4j_username}"
)

curl --fail --silent --show-error "${neo4j_http_url}/" >/dev/null
curl --fail --silent --show-error --header 'Accept: text/html' "${neo4j_http_url}/browser/" >/dev/null

"${cypher[@]}" \
  'CALL forward.plugin.info()
   YIELD version, configuredLicenseTier, forwardCredentialsConfigured,
         networkFacetEnabled, securityFacetEnabled, predictFacetEnabled
   RETURN *'

"${cypher[@]}" \
  'CALL forward.license.features()
   YIELD feature, enabled, requiredFacet
   RETURN * ORDER BY feature'

if ${verify_forward}; then
  "${cypher[@]}" \
    'CALL forward.testConnection()
     YIELD authenticated, accessibleNetworks, defaultNetworkConfigured,
           defaultNetworkVisible, durationMillis
     RETURN *'
fi

echo "Forward Neo4j local installation verification passed."
