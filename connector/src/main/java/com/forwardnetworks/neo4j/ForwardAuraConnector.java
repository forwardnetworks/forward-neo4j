package com.forwardnetworks.neo4j;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;

/** Client-side importer for AuraDB and other Neo4j servers that cannot load server plugins. */
public final class ForwardAuraConnector {

    private ForwardAuraConnector() {}

    public static void main(String[] args) {
        Arguments arguments = Arguments.parse(args);
        Map<String, String> environment = System.getenv();
        String uri = required(environment, "NEO4J_URI");
        requireSecureNeo4jUri(uri);
        String username = environment.getOrDefault("NEO4J_USERNAME", "neo4j");
        String password = password(environment);
        String database = environment.getOrDefault("NEO4J_DATABASE", "neo4j");

        try (var driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password))) {
            driver.verifyConnectivity();
            var importer = new ForwardExternalImporter(
                ForwardConfig.fromEnvironment(),
                driver,
                database,
                positiveInt(environment.get("FORWARD_NEO4J_BATCH_SIZE"), 5_000)
            );
            var summary = importer.importLatest(arguments.networkId(), arguments.snapshotId(), arguments.publish());
            System.out.printf(
                "Forward snapshot import complete: network=%s snapshot=%s devices=%d links=%d components=%d published=%s%n",
                summary.networkId(),
                summary.snapshotId(),
                summary.devices(),
                summary.links(),
                summary.components(),
                summary.published()
            );
        }
    }

    private static String password(Map<String, String> environment) {
        String direct = environment.get("NEO4J_PASSWORD");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String suppliedPath = environment.get("NEO4J_PASSWORD_FILE");
        if (suppliedPath == null || suppliedPath.isBlank()) {
            throw new IllegalStateException("Set NEO4J_PASSWORD or NEO4J_PASSWORD_FILE");
        }
        Path path = Path.of(suppliedPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("NEO4J_PASSWORD_FILE is not a regular file");
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))) {
                throw new IllegalStateException("NEO4J_PASSWORD_FILE must not grant group or other permissions");
            }
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw new IllegalStateException("NEO4J_PASSWORD_FILE is empty");
            }
            return value;
        } catch (UnsupportedOperationException e) {
            try {
                String value = Files.readString(path).strip();
                if (value.isEmpty()) {
                    throw new IllegalStateException("NEO4J_PASSWORD_FILE is empty");
                }
                return value;
            } catch (java.io.IOException readFailure) {
                throw new IllegalStateException("Unable to read NEO4J_PASSWORD_FILE", readFailure);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to read NEO4J_PASSWORD_FILE", e);
        }
    }

    private static void requireSecureNeo4jUri(String value) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        boolean encrypted = Set.of("neo4j+s", "neo4j+ssc", "bolt+s", "bolt+ssc").contains(scheme);
        boolean loopback = Set.of("localhost", "127.0.0.1", "::1").contains(uri.getHost());
        if (uri.getHost() == null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalStateException("NEO4J_URI must be a host URI without a path");
        }
        if (!encrypted && !loopback) {
            throw new IllegalStateException("NEO4J_URI must use an encrypted +s/+ssc scheme except for loopback testing");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException("NEO4J_URI must not contain credentials, query parameters, or a fragment");
        }
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name);
        }
        return value;
    }

    private static int positiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0 || parsed > 50_000) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("FORWARD_NEO4J_BATCH_SIZE must be between 1 and 50000", e);
        }
    }

    private record Arguments(String networkId, String snapshotId, boolean publish) {
        static Arguments parse(String[] args) {
            String networkId = "";
            String snapshotId = "";
            boolean publish = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--network" -> networkId = requireValue(args, ++index, "--network");
                    case "--snapshot" -> snapshotId = requireValue(args, ++index, "--snapshot");
                    case "--publish" -> publish = true;
                    case "--help", "-h" -> {
                        System.out.println("Usage: forward-neo4j-connector [--network ID] [--snapshot ID] [--publish]");
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
                }
            }
            return new Arguments(networkId, snapshotId, publish);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
