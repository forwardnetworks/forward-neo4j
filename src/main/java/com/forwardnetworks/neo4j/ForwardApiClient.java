package com.forwardnetworks.neo4j;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class ForwardApiClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> ROW_TYPE = new TypeReference<>() {};
    private static final int MAX_GET_ATTEMPTS = 3;
    private static final int MAX_PATH_QUERIES = 1_000;
    private static final Set<String> PATH_QUERY_FIELDS = Set.of(
        "from",
        "srcIp",
        "dstIp",
        "ipProto",
        "srcPort",
        "dstPort",
        "icmpType",
        "fin",
        "syn",
        "rst",
        "psh",
        "ack",
        "urg",
        "appId",
        "userId",
        "userGroupId",
        "url",
        "domain"
    );
    private static final Set<String> PATH_OPTION_FIELDS = Set.of(
        "intent",
        "maxCandidates",
        "maxResults",
        "maxReturnPathResults",
        "maxSeconds",
        "maxOverallSeconds",
        "includeTags",
        "includeNetworkFunctions"
    );

    private final ForwardConfig config;
    private final HttpClient httpClient;
    private final String authorization;

    ForwardApiClient(ForwardConfig config) {
        this(
            config,
            HttpClient.newBuilder().connectTimeout(config.timeout()).followRedirects(HttpClient.Redirect.NEVER).build()
        );
    }

    ForwardApiClient(ForwardConfig config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        String credentials = config.username() + ':' + config.password();
        authorization = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    ConnectionData testConnection() {
        JsonNode response = get("/api/networks");
        if (!response.isArray()) {
            throw new ForwardApiException("Networks API returned a non-array response");
        }
        boolean defaultVisible = config.defaultNetworkId() != null && stream(response).anyMatch(network ->
            config.defaultNetworkId().equals(optionalText(network, "id"))
        );
        return new ConnectionData(response.size(), config.defaultNetworkId() != null, defaultVisible);
    }

    SnapshotData selectSnapshot(String networkId, String requestedSnapshotId) {
        if (requestedSnapshotId != null && !requestedSnapshotId.isBlank()) {
            return new SnapshotData(requestedSnapshotId, null, null);
        }

        JsonNode response = get(
            "/api/networks/" + segment(networkId) + "/snapshots?state=PROCESSED&includeArchived=false"
        );
        JsonNode snapshots = response.path("snapshots");
        if (!snapshots.isArray() || snapshots.isEmpty()) {
            throw new ForwardApiException("Network has no processed snapshots");
        }
        return stream(snapshots)
            .map(node ->
                new SnapshotData(
                    requiredText(node, "id"),
                    optionalText(node, "createdAt"),
                    optionalText(node, "processedAt")
                )
            )
            .max(Comparator.comparing(ForwardApiClient::snapshotSortInstant))
            .orElseThrow();
    }

    List<DeviceData> getDevices(String networkId, String snapshotId) {
        JsonNode response = get(
            "/api/networks/" +
            segment(networkId) +
            "/devices?snapshotId=" +
            query(snapshotId) +
            "&with=tags&with=locationId"
        );
        if (!response.isArray()) {
            throw new ForwardApiException("Devices API returned a non-array response");
        }
        List<DeviceData> devices = new ArrayList<>();
        response.forEach(node ->
            devices.add(
                new DeviceData(
                    requiredText(node, "name"),
                    optionalText(node, "displayName"),
                    optionalText(node, "type"),
                    optionalText(node, "vendor"),
                    optionalText(node, "model"),
                    optionalText(node, "platform"),
                    optionalText(node, "osVersion"),
                    optionalText(node, "locationId"),
                    textList(node.path("managementIps")),
                    textList(node.path("tags"))
                )
            )
        );
        return List.copyOf(devices);
    }

    Stream<DeviceData> streamDevices(String networkId, String snapshotId, Runnable cancellationCheck) {
        return streamArray(
            "/api/networks/" +
            segment(networkId) +
            "/devices?snapshotId=" +
            query(snapshotId) +
            "&with=tags&with=locationId",
            "Devices API",
            cancellationCheck,
            ForwardApiClient::deviceData
        );
    }

    List<TopologyLinkData> getTopology(String snapshotId) {
        JsonNode response = get("/api/snapshots/" + segment(snapshotId) + "/topology");
        if (!response.isArray()) {
            throw new ForwardApiException("Topology API returned a non-array response");
        }
        List<TopologyLinkData> links = new ArrayList<>();
        response.forEach(node ->
            links.add(
                new TopologyLinkData(requiredText(node, "sourcePort"), requiredText(node, "targetPort"))
            )
        );
        return List.copyOf(links);
    }

    Stream<TopologyLinkData> streamTopology(String snapshotId, Runnable cancellationCheck) {
        return streamArray(
            "/api/snapshots/" + segment(snapshotId) + "/topology",
            "Topology API",
            cancellationCheck,
            node -> new TopologyLinkData(requiredText(node, "sourcePort"), requiredText(node, "targetPort"))
        );
    }

    List<Map<String, Object>> getChecks(String snapshotId) {
        return objectRows(get("/api/snapshots/" + segment(snapshotId) + "/checks"), "Checks API");
    }

    Map<String, Object> getCheck(String snapshotId, String checkId) {
        return objectRow(
            get("/api/snapshots/" + segment(snapshotId) + "/checks/" + segment(checkId)),
            "Check details API"
        );
    }

    VulnerabilityAnalysisData getVulnerabilities(String networkId, String snapshotId) {
        JsonNode response = get(
            "/api/networks/" +
            segment(networkId) +
            "/vulnerabilities?v=2&snapshotId=" +
            query(snapshotId)
        );
        if (!response.isObject()) {
            throw new ForwardApiException("Vulnerability Analysis API returned a non-object response");
        }
        return new VulnerabilityAnalysisData(
            objectRows(response.path("vulnerabilities"), "Vulnerability Analysis API vulnerabilities"),
            optionalText(response, "indexCreatedAt"),
            optionalText(response, "indexUploadedAt")
        );
    }

    Map<String, Object> getVulnerability(String networkId, String snapshotId, String cveId) {
        return objectRow(
            get(
                "/api/networks/" +
                segment(networkId) +
                "/vulnerabilities/" +
                segment(cveId) +
                "?snapshotId=" +
                query(snapshotId)
            ),
            "Vulnerability details API"
        );
    }

    List<PathSearchData> getPathsBulk(
        String networkId,
        String snapshotId,
        List<Map<String, Object>> flows,
        Map<String, Object> options
    ) {
        if (flows == null || flows.isEmpty()) {
            throw new IllegalArgumentException("At least one curated path flow is required");
        }
        if (flows.size() > MAX_PATH_QUERIES) {
            throw new IllegalArgumentException("A path request may contain at most " + MAX_PATH_QUERIES + " flows");
        }

        List<String> names = new ArrayList<>(flows.size());
        List<Map<String, Object>> queries = new ArrayList<>(flows.size());
        Set<String> uniqueNames = new HashSet<>();
        for (Map<String, Object> flow : flows) {
            if (flow == null) {
                throw new IllegalArgumentException("Path flow must be a map");
            }
            Object nameValue = flow.get("name");
            String name = nameValue instanceof String string ? string.strip() : "";
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Every path flow requires a non-empty name");
            }
            if (!uniqueNames.add(name)) {
                throw new IllegalArgumentException("Path flow names must be unique: " + name);
            }
            LinkedHashMap<String, Object> pathQuery = new LinkedHashMap<>();
            for (var entry : flow.entrySet()) {
                if ("name".equals(entry.getKey())) {
                    continue;
                }
                if (!PATH_QUERY_FIELDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Unsupported path query field '" + entry.getKey() + "'");
                }
                if (entry.getValue() != null) {
                    pathQuery.put(entry.getKey(), entry.getValue());
                }
            }
            requireNonBlankString(pathQuery, "dstIp", name);
            if (!hasNonBlankString(pathQuery, "from") && !hasNonBlankString(pathQuery, "srcIp")) {
                throw new IllegalArgumentException("Path flow '" + name + "' requires either from or srcIp");
            }
            names.add(name);
            queries.add(Collections.unmodifiableMap(pathQuery));
        }

        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.set("queries", OBJECT_MAPPER.valueToTree(queries));
        if (options != null) {
            for (var entry : options.entrySet()) {
                if (!PATH_OPTION_FIELDS.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Unsupported path request option '" + entry.getKey() + "'");
                }
                if (entry.getValue() != null) {
                    body.set(entry.getKey(), OBJECT_MAPPER.valueToTree(entry.getValue()));
                }
            }
        }

        JsonNode response = post(
            "/api/networks/" + segment(networkId) + "/paths-bulk?snapshotId=" + query(snapshotId),
            body
        );
        if (!response.isArray()) {
            throw new ForwardApiException("Bulk Path Search API returned a non-array response");
        }
        if (response.size() != flows.size()) {
            throw new ForwardApiException("Bulk Path Search API result count did not match the request count");
        }
        List<PathSearchData> results = new ArrayList<>(response.size());
        for (int index = 0; index < response.size(); index++) {
            results.add(new PathSearchData(index, names.get(index), queries.get(index), objectRow(response.get(index), "Bulk Path Search result")));
        }
        return List.copyOf(results);
    }

    NqeExecution executeNqe(
        String networkId,
        String snapshotId,
        NqeProjection projection,
        Runnable cancellationCheck
    ) {
        cancellationCheck.run();
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("query", projection.source());
        JsonNode submitted = post(
            "/api/networks/" +
            segment(networkId) +
            "/nqe-executions?snapshotId=" +
            query(snapshotId),
            body
        );
        String executionKey = requiredText(submitted, "executionKey");
        JsonNode status = submitted;
        Instant deadline = Instant.now().plus(config.nqeTimeout());
        while (!"COMPLETED".equals(optionalText(status, "status"))) {
            cancellationCheck.run();
            if (!Instant.now().isBefore(deadline)) {
                throw new ForwardApiException(
                    "NQE projection '" + projection.projectionName() + "' did not complete within " + config.nqeTimeout()
                );
            }
            sleep(config.nqePollInterval());
            cancellationCheck.run();
            status = get(
                "/api/networks/" + segment(networkId) + "/nqe-executions/" + segment(executionKey)
            );
        }

        String outcome = optionalText(status, "outcome");
        if (!"OK".equals(outcome)) {
            String diagnostic = optionalText(status.path("error"), "message");
            throw new ForwardApiException(
                "NQE projection '" +
                projection.projectionName() +
                "' completed with outcome " +
                outcome +
                (diagnostic == null ? "" : ": " + diagnostic)
            );
        }
        long rowsProduced = status.path("rowsProduced").asLong(-1);
        return new NqeExecution(executionKey, rowsProduced);
    }

    Iterator<Map<String, Object>> nqeRows(
        String networkId,
        String snapshotId,
        String executionKey,
        Runnable cancellationCheck
    ) {
        return new NqeResultIterator(
            this,
            networkId,
            snapshotId,
            executionKey,
            config.nqePageSize(),
            cancellationCheck
        );
    }

    private NqePage getNqePage(
        String networkId,
        String expectedSnapshotId,
        String executionKey,
        int offset,
        int limit
    ) {
        JsonNode response = get(
            "/api/networks/" +
            segment(networkId) +
            "/nqe-executions/" +
            segment(executionKey) +
            "/result?offset=" +
            offset +
            "&limit=" +
            limit
        );
        String actualSnapshotId = optionalText(response, "snapshotId");
        if (actualSnapshotId != null && !expectedSnapshotId.equals(actualSnapshotId)) {
            throw new ForwardApiException("NQE result snapshot did not match the requested snapshot");
        }
        JsonNode items = response.path("items");
        if (!items.isArray()) {
            throw new ForwardApiException("NQE result did not contain an items array");
        }
        List<Map<String, Object>> rows = new ArrayList<>(items.size());
        items.forEach(row -> {
            if (!row.isObject()) {
                throw new ForwardApiException("NQE result contained a non-object row");
            }
            rows.add(OBJECT_MAPPER.convertValue(row, ROW_TYPE));
        });
        return new NqePage(List.copyOf(rows), response.path("totalNumItems").asLong(-1));
    }

    private JsonNode get(String pathAndQuery) {
        return request("GET", pathAndQuery, null, true);
    }

    private <T> Stream<T> streamArray(
        String pathAndQuery,
        String source,
        Runnable cancellationCheck,
        Function<JsonNode, T> mapper
    ) {
        InputStream body = getStream(pathAndQuery);
        StreamingArrayIterator<T> iterator;
        try {
            iterator = new StreamingArrayIterator<>(body, source, cancellationCheck, mapper);
        } catch (RuntimeException e) {
            try {
                body.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        return StreamSupport.stream(
            java.util.Spliterators.spliteratorUnknownSize(
                iterator,
                java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL
            ),
            false
        ).onClose(iterator::close);
    }

    private InputStream getStream(String pathAndQuery) {
        URI uri = config.baseUri().resolve(pathAndQuery);
        IOException lastIoFailure = null;
        for (int attempt = 1; attempt <= MAX_GET_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .header("Accept", "application/json")
                .header("Authorization", authorization)
                .GET()
                .build();
            try {
                HttpResponse<InputStream> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
                try (InputStream ignored = response.body()) {
                    // Closing the response body permits connection reuse without exposing error contents.
                }
                if (attempt < MAX_GET_ATTEMPTS && isTransient(response.statusCode())) {
                    sleep(Duration.ofMillis(250L << (attempt - 1)));
                    continue;
                }
                throw new ForwardApiException(
                    "Forward GET " + uri.getPath() + " returned HTTP " + response.statusCode()
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ForwardApiException("Forward GET was interrupted", e);
            } catch (IOException e) {
                lastIoFailure = e;
                if (attempt < MAX_GET_ATTEMPTS) {
                    sleep(Duration.ofMillis(250L << (attempt - 1)));
                    continue;
                }
            }
        }
        throw new ForwardApiException("Forward GET failed: " + lastIoFailure.getMessage(), lastIoFailure);
    }

    private JsonNode post(String pathAndQuery, JsonNode body) {
        return request("POST", pathAndQuery, body, false);
    }

    private JsonNode request(String method, String pathAndQuery, JsonNode body, boolean retryable) {
        URI uri = config.baseUri().resolve(pathAndQuery);
        int attempts = retryable ? MAX_GET_ATTEMPTS : 1;
        IOException lastIoFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(config.timeout())
                .header("Accept", "application/json")
                .header("Authorization", authorization);
            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            }
            try {
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return OBJECT_MAPPER.readTree(response.body());
                }
                if (attempt < attempts && isTransient(response.statusCode())) {
                    sleep(Duration.ofMillis(250L << (attempt - 1)));
                    continue;
                }
                throw new ForwardApiException(
                    "Forward " + method + " " + uri.getPath() + " returned HTTP " + response.statusCode()
                );
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ForwardApiException("Forward " + method + " was interrupted", e);
            } catch (IOException e) {
                lastIoFailure = e;
                if (attempt < attempts) {
                    sleep(Duration.ofMillis(250L << (attempt - 1)));
                    continue;
                }
            }
        }
        throw new ForwardApiException("Forward " + method + " failed: " + lastIoFailure.getMessage(), lastIoFailure);
    }

    private static boolean isTransient(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForwardApiException("Forward request was interrupted", e);
        }
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }

    private static Instant snapshotSortInstant(SnapshotData snapshot) {
        String value = snapshot.processedAt() != null ? snapshot.processedAt() : snapshot.createdAt();
        return value == null ? Instant.EPOCH : Instant.parse(value);
    }

    private static String requiredText(JsonNode node, String property) {
        String value = optionalText(node, property);
        if (value == null || value.isBlank()) {
            throw new ForwardApiException("Forward response is missing required property '" + property + "'");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String property) {
        JsonNode value = node.path(property);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static List<String> textList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static DeviceData deviceData(JsonNode node) {
        return new DeviceData(
            requiredText(node, "name"),
            optionalText(node, "displayName"),
            optionalText(node, "type"),
            optionalText(node, "vendor"),
            optionalText(node, "model"),
            optionalText(node, "platform"),
            optionalText(node, "osVersion"),
            optionalText(node, "locationId"),
            textList(node.path("managementIps")),
            textList(node.path("tags"))
        );
    }

    private static List<Map<String, Object>> objectRows(JsonNode node, String source) {
        if (!node.isArray()) {
            throw new ForwardApiException(source + " returned a non-array response");
        }
        List<Map<String, Object>> rows = new ArrayList<>(node.size());
        node.forEach(item -> rows.add(objectRow(item, source)));
        return List.copyOf(rows);
    }

    private static Map<String, Object> objectRow(JsonNode node, String source) {
        if (!node.isObject()) {
            throw new ForwardApiException(source + " contained a non-object item");
        }
        return Collections.unmodifiableMap(OBJECT_MAPPER.convertValue(node, ROW_TYPE));
    }

    private static void requireNonBlankString(Map<String, Object> values, String field, String flowName) {
        if (!hasNonBlankString(values, field)) {
            throw new IllegalArgumentException("Path flow '" + flowName + "' requires " + field);
        }
    }

    private static boolean hasNonBlankString(Map<String, Object> values, String field) {
        Object value = values.get(field);
        return value instanceof String string && !string.isBlank();
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    record ConnectionData(long accessibleNetworks, boolean defaultNetworkConfigured, boolean defaultNetworkVisible) {}

    record SnapshotData(String id, String createdAt, String processedAt) {}

    record DeviceData(
        String name,
        String displayName,
        String type,
        String vendor,
        String model,
        String platform,
        String osVersion,
        String locationId,
        List<String> managementIps,
        List<String> tags
    ) {}

    record TopologyLinkData(String sourcePort, String targetPort) {}

    record NqeExecution(String key, long rowsProduced) {}

    record VulnerabilityAnalysisData(
        List<Map<String, Object>> vulnerabilities,
        String indexCreatedAt,
        String indexUploadedAt
    ) {}

    record PathSearchData(
        long index,
        String name,
        Map<String, Object> query,
        Map<String, Object> result
    ) {}

    private record NqePage(List<Map<String, Object>> rows, long totalItems) {}

    private static final class StreamingArrayIterator<T> implements Iterator<T>, AutoCloseable {

        private final JsonParser parser;
        private final String source;
        private final Runnable cancellationCheck;
        private final Function<JsonNode, T> mapper;
        private T next;
        private boolean nextReady;
        private boolean closed;

        private StreamingArrayIterator(
            InputStream body,
            String source,
            Runnable cancellationCheck,
            Function<JsonNode, T> mapper
        ) {
            this.source = source;
            this.cancellationCheck = cancellationCheck;
            this.mapper = mapper;
            try {
                parser = OBJECT_MAPPER.getFactory().createParser(body);
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    close();
                    throw new ForwardApiException(source + " returned a non-array response");
                }
            } catch (IOException e) {
                throw new ForwardApiException("Unable to parse " + source + " response", e);
            }
        }

        @Override
        public boolean hasNext() {
            if (nextReady) {
                return true;
            }
            if (closed) {
                return false;
            }
            cancellationCheck.run();
            try {
                JsonToken token = parser.nextToken();
                if (token == JsonToken.END_ARRAY) {
                    close();
                    return false;
                }
                if (token == null) {
                    close();
                    throw new ForwardApiException(source + " response ended before the array was complete");
                }
                if (token != JsonToken.START_OBJECT) {
                    close();
                    throw new ForwardApiException(source + " contained a non-object item");
                }
                next = mapper.apply(OBJECT_MAPPER.readTree(parser));
                nextReady = true;
                return true;
            } catch (IOException e) {
                close();
                throw new ForwardApiException("Unable to stream " + source + " response", e);
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            T value = next;
            next = null;
            nextReady = false;
            return value;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                parser.close();
            } catch (IOException e) {
                throw new ForwardApiException("Unable to close " + source + " response", e);
            }
        }
    }

    private static final class NqeResultIterator implements Iterator<Map<String, Object>> {

        private final ForwardApiClient client;
        private final String networkId;
        private final String snapshotId;
        private final String executionKey;
        private final int pageSize;
        private final Runnable cancellationCheck;
        private final Deque<Map<String, Object>> buffered = new ArrayDeque<>();
        private int offset;
        private long totalItems = -1;
        private boolean exhausted;

        private NqeResultIterator(
            ForwardApiClient client,
            String networkId,
            String snapshotId,
            String executionKey,
            int pageSize,
            Runnable cancellationCheck
        ) {
            this.client = client;
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.executionKey = executionKey;
            this.pageSize = pageSize;
            this.cancellationCheck = cancellationCheck;
        }

        @Override
        public boolean hasNext() {
            fill();
            return !buffered.isEmpty();
        }

        @Override
        public Map<String, Object> next() {
            fill();
            if (buffered.isEmpty()) {
                throw new NoSuchElementException();
            }
            return buffered.removeFirst();
        }

        private void fill() {
            if (!buffered.isEmpty() || exhausted) {
                return;
            }
            cancellationCheck.run();
            NqePage page = client.getNqePage(networkId, snapshotId, executionKey, offset, pageSize);
            if (page.totalItems() >= 0) {
                totalItems = page.totalItems();
            }
            buffered.addAll(page.rows());
            offset += page.rows().size();
            exhausted = page.rows().isEmpty() || (totalItems >= 0 && offset >= totalItems);
        }
    }
}
