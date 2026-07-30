package com.forwardnetworks.neo4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

enum NqeProjection {
    INTERFACES("interfaces-v1", "1", "/nqe/interfaces-v1.nqe", ForwardFeature.INTERFACE_NQE),
    INTERFACE_ADDRESSES("interface-addresses-v1", "1", "/nqe/interface-addresses-v1.nqe", ForwardFeature.INTERFACE_NQE),
    NETWORK_INSTANCES("network-instances-v1", "1", "/nqe/network-instances-v1.nqe", ForwardFeature.ROUTING_NQE),
    BGP_NEIGHBORS("bgp-neighbors-v1", "1", "/nqe/bgp-neighbors-v1.nqe", ForwardFeature.ROUTING_NQE);

    private final String name;
    private final String version;
    private final String source;
    private final String sha256;
    private final ForwardFeature feature;

    NqeProjection(String name, String version, String resource, ForwardFeature feature) {
        this.name = name;
        this.version = version;
        this.source = readResource(resource);
        this.sha256 = sha256(source);
        this.feature = feature;
    }

    String projectionName() {
        return name;
    }

    String version() {
        return version;
    }

    String source() {
        return source;
    }

    String sha256() {
        return sha256;
    }

    ForwardFeature feature() {
        return feature;
    }

    static NqeProjection named(String name) {
        return Arrays.stream(values())
            .filter(projection -> projection.name.equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown NQE projection '" + name + "'. Call forward.nqe.projections() for supported names."
            ));
    }

    private static String readResource(String resource) {
        try (InputStream input = NqeProjection.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled NQE resource " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip() + '\n';
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read bundled NQE resource " + resource, e);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not provide SHA-256", e);
        }
    }
}
