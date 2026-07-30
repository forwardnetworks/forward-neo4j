package com.forwardnetworks.neo4j;

import java.util.Arrays;
import java.util.Locale;

enum ImportComponent {
    INVENTORY("inventory", ForwardFeature.INVENTORY, "Device inventory and physical topology"),
    INTERFACES("interfaces-v1", ForwardFeature.INTERFACE_NQE, "Interface properties"),
    INTERFACE_ADDRESSES("interface-addresses-v1", ForwardFeature.INTERFACE_NQE, "Interface IP addresses"),
    NETWORK_INSTANCES("network-instances-v1", ForwardFeature.ROUTING_NQE, "Network instances and route counts"),
    BGP_NEIGHBORS("bgp-neighbors-v1", ForwardFeature.ROUTING_NQE, "Directional BGP neighbor observations"),
    CHECKS("checks", ForwardFeature.CHECKS, "Check status observations"),
    CHECK_DETAILS("check-details", ForwardFeature.CHECKS, "Selected check diagnosis details"),
    VULNERABILITIES("vulnerabilities", ForwardFeature.VULNERABILITIES, "Vulnerability summary observations"),
    VULNERABILITY_DETAILS("vulnerability-details", ForwardFeature.VULNERABILITIES, "Selected vulnerability device findings"),
    PATHS("paths", ForwardFeature.PATHS, "Curated path observations"),
    BUSINESS_CONTEXT("business-context", ForwardFeature.INVENTORY, "Operator-supplied business context");

    private final String id;
    private final ForwardFeature feature;
    private final String description;

    ImportComponent(String id, ForwardFeature feature, String description) {
        this.id = id;
        this.feature = feature;
        this.description = description;
    }

    String id() {
        return id;
    }

    ForwardFeature feature() {
        return feature;
    }

    String description() {
        return description;
    }

    static ImportComponent named(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(component -> component.id.equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown import component '" + value + "'. Supported components: " +
                String.join(", ", Arrays.stream(values()).map(ImportComponent::id).toList())
            ));
    }
}
