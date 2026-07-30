# License tiers and feature selection

Forward's modular license model has Base, Network, Security, and Predict facets. Valid tiers are `B`, `N`, `NP`, `S`,
`NS`, and `NSP`; every tier includes Base, and Predict is always paired with Network.

The integration uses an explicit selector because deployment credentials may not have access to license-administration
data. Set the tier that the Forward organization actually has:

```ini
Environment=FORWARD_LICENSE_TIER=NS
```

The default is `B`. An invalid value fails configuration, and a disabled procedure fails before its feature API is
called. This is a guardrail, not an entitlement mechanism: setting `NS` cannot grant Forward access that the
organization does not own.

## Feature matrix

| Integration capability | Requirement | B | N | NP | S | NS | NSP |
|---|---|:---:|:---:|:---:|:---:|:---:|:---:|
| Snapshots, devices, physical topology | Base | yes | yes | yes | yes | yes | yes |
| `interfaces-v1` NQE | Base-compatible model | yes | yes | yes | yes | yes | yes |
| `interface-addresses-v1` NQE | Base-compatible model | yes | yes | yes | yes | yes | yes |
| `network-instances-v1` NQE | Network | no | yes | yes | no | yes | yes |
| `bgp-neighbors-v1` NQE | Network | no | yes | yes | no | yes | yes |
| Checks | Network or Security | no | yes | yes | yes | yes | yes |
| Bulk Path Search | Network or Security | no | yes | yes | yes | yes | yes |
| Vulnerability Analysis | Security | no | no | no | yes | yes | yes |

Predict does not add a separate integration feature today, so `N` and `NP` have the same rows above, as do `NS` and
`NSP`. The tier is still preserved in runtime reporting for future Predict-specific procedures.

## What the Base inventory tier gets

`B` is intentionally limited but not empty. It can build the temporal inventory/physical-topology graph and import the
two NQEs that stay within the Base-visible model. It does **not** enable VRF/BGP projections, Checks, Path Search, or
Vulnerability Analysis. Teams looking for assurance, forwarding behavior, routing structure, or security correlation
need the corresponding Network or Security facet.

## Network versus Security

- Network adds routing-model NQEs and shares Checks and Path Search.
- Security adds Vulnerability Analysis and shares Checks and Path Search.
- Security-only does not expose network-instance/BGP model paths, so those projections remain disabled for `S`.
- Network plus Security (`NS` or `NSP`) enables the entire current integration.

This split follows current code-level operation and NQE-model restrictions. Commercial packaging can evolve; verify the
organization's actual entitlement for the Forward release being integrated.

## Inspect the selection

No Forward request is needed to inspect the local guardrail:

```cypher
CALL forward.plugin.info()
YIELD configuredLicenseTier, networkFacetEnabled, securityFacetEnabled, predictFacetEnabled
RETURN *;

CALL forward.license.features()
YIELD feature, enabled, requiredFacet, description
RETURN * ORDER BY feature;

CALL forward.nqe.projections()
YIELD name, version, sha256, enabled, requiredFacet
RETURN * ORDER BY name;
```

Feature groups remain individually selectable after choosing a tier: run only the import scripts needed for the graph.
For example, an `NS` deployment can import Checks without importing vulnerabilities or path observations.
