package com.forwardnetworks.neo4j;

import java.util.Arrays;

enum ForwardLicenseTier {
    B(false, false, false),
    N(true, false, false),
    NP(true, false, true),
    S(false, true, false),
    NS(true, true, false),
    NSP(true, true, true);

    private final boolean network;
    private final boolean security;
    private final boolean predict;

    ForwardLicenseTier(boolean network, boolean security, boolean predict) {
        this.network = network;
        this.security = security;
        this.predict = predict;
    }

    boolean network() {
        return network;
    }

    boolean security() {
        return security;
    }

    boolean predict() {
        return predict;
    }

    boolean enables(ForwardFeature feature) {
        return switch (feature) {
            case INVENTORY, INTERFACE_NQE -> true;
            case ROUTING_NQE -> network;
            case CHECKS, PATHS -> network || security;
            case VULNERABILITIES -> security;
        };
    }

    void require(ForwardFeature feature) {
        if (!enables(feature)) {
            throw new IllegalStateException(
                "Forward feature '" + feature.id() + "' requires " + feature.requirement() +
                "; configured FORWARD_LICENSE_TIER is " + name()
            );
        }
    }

    static ForwardLicenseTier parse(String value) {
        String normalized = value == null || value.isBlank() ? "B" : value.strip().toUpperCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
            .filter(tier -> tier.name().equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "FORWARD_LICENSE_TIER must be one of B, N, NP, S, NS, or NSP"
            ));
    }
}

enum ForwardFeature {
    INVENTORY("inventory-topology", "BASE", "Snapshots, device inventory, and physical topology"),
    INTERFACE_NQE("interface-nqe", "BASE", "Interface and interface-address NQE projections"),
    ROUTING_NQE("routing-nqe", "NETWORK", "Network-instance and BGP NQE projections"),
    CHECKS("checks", "NETWORK_OR_SECURITY", "Snapshot verification observations"),
    PATHS("paths", "NETWORK_OR_SECURITY", "Curated Bulk Path Search observations"),
    VULNERABILITIES("vulnerabilities", "SECURITY", "CVE summaries and device-level details");

    private final String id;
    private final String requirement;
    private final String description;

    ForwardFeature(String id, String requirement, String description) {
        this.id = id;
        this.requirement = requirement;
        this.description = description;
    }

    String id() {
        return id;
    }

    String requirement() {
        return requirement;
    }

    String description() {
        return description;
    }
}
