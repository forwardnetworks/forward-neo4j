package com.forwardnetworks.neo4j;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

record ForwardConfig(
    URI baseUri,
    String username,
    String password,
    String defaultNetworkId,
    Duration timeout,
    Duration nqePollInterval,
    Duration nqeTimeout,
    int nqePageSize,
    ForwardLicenseTier licenseTier
) {

    private static final int DEFAULT_HTTP_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_NQE_POLL_SECONDS = 5;
    private static final int DEFAULT_NQE_TIMEOUT_SECONDS = 1800;
    private static final int DEFAULT_NQE_PAGE_SIZE = 5000;

    static ForwardConfig fromEnvironment() {
        return from(System.getenv(), System.getProperties());
    }

    static ForwardConfig from(Map<String, String> environment, java.util.Properties properties) {
        String url = setting(environment, properties, "FORWARD_URL", "forward.url");
        String token = setting(environment, properties, "FORWARD_TOKEN", "forward.token");
        String username = setting(environment, properties, "FORWARD_USERNAME", "forward.username");
        String password = setting(environment, properties, "FORWARD_PASSWORD", "forward.password");
        String defaultNetworkId = setting(
            environment,
            properties,
            "FORWARD_DEFAULT_NETWORK_ID",
            "forward.defaultNetworkId"
        );

        String credentialFile = setting(
            environment,
            properties,
            "FORWARD_CREDENTIAL_FILE",
            "forward.credentialFile"
        );
        if (credentialFile != null && !credentialFile.isBlank()) {
            CredentialFile values = readCredentialFile(Path.of(credentialFile));
            username = firstPresent(username, values.username());
            password = firstPresent(password, values.password());
            url = firstPresent(url, values.url());
            defaultNetworkId = firstPresent(defaultNetworkId, values.defaultNetworkId());
        }

        if (token != null && !token.isBlank()) {
            username = token;
            password = "";
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Set FORWARD_URL or provide it in FORWARD_CREDENTIAL_FILE");
        }
        if (username == null || password == null) {
            throw new IllegalStateException(
                "Set FORWARD_TOKEN, both FORWARD_USERNAME and FORWARD_PASSWORD, or FORWARD_CREDENTIAL_FILE"
            );
        }

        String normalizedUrl = url.contains("://") ? url : "https://" + url;
        URI baseUri = URI.create(
            normalizedUrl.endsWith("/") ? normalizedUrl.substring(0, normalizedUrl.length() - 1) : normalizedUrl
        );
        if (!"https".equalsIgnoreCase(baseUri.getScheme()) && !isLoopback(baseUri)) {
            throw new IllegalStateException("FORWARD_URL must use HTTPS except for loopback test servers");
        }
        if (
            baseUri.getHost() == null ||
            baseUri.getRawUserInfo() != null ||
            baseUri.getRawQuery() != null ||
            (baseUri.getPath() != null && !baseUri.getPath().isEmpty() && !"/".equals(baseUri.getPath()))
        ) {
            throw new IllegalStateException(
                "FORWARD_URL must be a host URL without credentials, a path, or a query string"
            );
        }

        int httpTimeout = positiveInt(
            setting(environment, properties, "FORWARD_HTTP_TIMEOUT_SECONDS", "forward.httpTimeoutSeconds"),
            DEFAULT_HTTP_TIMEOUT_SECONDS,
            "FORWARD_HTTP_TIMEOUT_SECONDS"
        );
        int nqePollSeconds = nonNegativeInt(
            setting(environment, properties, "FORWARD_NQE_POLL_SECONDS", "forward.nqePollSeconds"),
            DEFAULT_NQE_POLL_SECONDS,
            "FORWARD_NQE_POLL_SECONDS"
        );
        int nqeTimeoutSeconds = positiveInt(
            setting(environment, properties, "FORWARD_NQE_TIMEOUT_SECONDS", "forward.nqeTimeoutSeconds"),
            DEFAULT_NQE_TIMEOUT_SECONDS,
            "FORWARD_NQE_TIMEOUT_SECONDS"
        );
        int pageSize = positiveInt(
            setting(environment, properties, "FORWARD_NQE_PAGE_SIZE", "forward.nqePageSize"),
            DEFAULT_NQE_PAGE_SIZE,
            "FORWARD_NQE_PAGE_SIZE"
        );
        if (pageSize > 10_000) {
            throw new IllegalStateException("FORWARD_NQE_PAGE_SIZE cannot exceed 10000");
        }
        ForwardLicenseTier licenseTier = licenseTier(environment, properties);

        return new ForwardConfig(
            baseUri,
            username,
            password,
            blankToNull(defaultNetworkId),
            Duration.ofSeconds(httpTimeout),
            Duration.ofSeconds(nqePollSeconds),
            Duration.ofSeconds(nqeTimeoutSeconds),
            pageSize,
            licenseTier
        );
    }

    String resolveNetworkId(String requestedNetworkId) {
        String requested = blankToNull(requestedNetworkId);
        if (requested != null) {
            return requested;
        }
        if (defaultNetworkId == null) {
            throw new IllegalStateException(
                "networkId is required unless FORWARD_DEFAULT_NETWORK_ID or a fourth credential-file line is set"
            );
        }
        return defaultNetworkId;
    }

    static ConfigurationStatus status(Map<String, String> environment, java.util.Properties properties) {
        String credentialFile = setting(
            environment,
            properties,
            "FORWARD_CREDENTIAL_FILE",
            "forward.credentialFile"
        );
        try {
            ForwardConfig config = from(environment, properties);
            return new ConfigurationStatus(true, true, credentialFile != null && !credentialFile.isBlank(), config.defaultNetworkId != null);
        } catch (RuntimeException e) {
            boolean urlConfigured = setting(environment, properties, "FORWARD_URL", "forward.url") != null ||
                (credentialFile != null && !credentialFile.isBlank());
            return new ConfigurationStatus(urlConfigured, false, credentialFile != null && !credentialFile.isBlank(), false);
        }
    }

    static ForwardLicenseTier licenseTier(Map<String, String> environment, java.util.Properties properties) {
        return ForwardLicenseTier.parse(
            setting(environment, properties, "FORWARD_LICENSE_TIER", "forward.licenseTier")
        );
    }

    private static String setting(
        Map<String, String> environment,
        java.util.Properties properties,
        String environmentName,
        String propertyName
    ) {
        return environment.getOrDefault(environmentName, properties.getProperty(propertyName));
    }

    private static CredentialFile readCredentialFile(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalStateException("FORWARD_CREDENTIAL_FILE is not a regular file");
        }
        requirePrivatePermissions(absolute);
        try {
            List<String> lines = Files.readAllLines(absolute)
                .stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
            if (lines.size() < 3 || lines.size() > 4) {
                throw new IllegalStateException(
                    "FORWARD_CREDENTIAL_FILE must contain username, password, host URL, and optionally network ID"
                );
            }
            return new CredentialFile(lines.get(0), lines.get(1), lines.get(2), lines.size() == 4 ? lines.get(3) : null);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read FORWARD_CREDENTIAL_FILE", e);
        }
    }

    private static void requirePrivatePermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            if (
                permissions.contains(PosixFilePermission.GROUP_READ) ||
                permissions.contains(PosixFilePermission.GROUP_WRITE) ||
                permissions.contains(PosixFilePermission.GROUP_EXECUTE) ||
                permissions.contains(PosixFilePermission.OTHERS_READ) ||
                permissions.contains(PosixFilePermission.OTHERS_WRITE) ||
                permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
            ) {
                throw new IllegalStateException("FORWARD_CREDENTIAL_FILE must not grant group or other permissions");
            }
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystems cannot expose Unix mode bits. File readability is still checked above.
        } catch (IOException e) {
            throw new IllegalStateException("Unable to inspect FORWARD_CREDENTIAL_FILE permissions", e);
        }
    }

    private static String firstPresent(String explicit, String fallback) {
        return explicit == null || explicit.isBlank() ? fallback : explicit;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int positiveInt(String value, int defaultValue, String settingName) {
        int parsed = nonNegativeInt(value, defaultValue, settingName);
        if (parsed == 0) {
            throw new IllegalStateException(settingName + " must be positive");
        }
        return parsed;
    }

    private static int nonNegativeInt(String value, int defaultValue, String settingName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalStateException(settingName + " must not be negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(settingName + " must be an integer", e);
        }
    }

    private static boolean isLoopback(URI uri) {
        return "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
    }

    record ConfigurationStatus(
        boolean urlConfigured,
        boolean credentialsConfigured,
        boolean credentialFileConfigured,
        boolean defaultNetworkConfigured
    ) {}

    private record CredentialFile(String username, String password, String url, String defaultNetworkId) {}
}
