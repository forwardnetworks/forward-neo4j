package com.forwardnetworks.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4j;
import org.neo4j.harness.Neo4jBuilders;

class ForwardProceduresTest {

    @AfterEach
    void clearForwardProperties() {
        System.clearProperty("forward.url");
        System.clearProperty("forward.username");
        System.clearProperty("forward.password");
        System.clearProperty("forward.nqePollSeconds");
        System.clearProperty("forward.nqePageSize");
        System.clearProperty("forward.licenseTier");
    }

    @Test
    void exposesPluginInfo() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build()) {
            try (var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())) {
                var record = driver.executableQuery("CALL forward.plugin.info()")
                    .execute()
                    .records()
                    .getFirst();

                assertEquals(ForwardProcedures.VERSION, record.get("version").asString());
                assertFalse(record.get("forwardUrlConfigured").isNull());
                assertFalse(record.get("forwardTokenConfigured").isNull());
            }
        }
    }

    @Test
    void installsSchemaIdempotently() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build()) {
            try (var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())) {
                assertEquals(
                    15,
                    driver.executableQuery("CALL forward.schema.install()")
                        .execute()
                        .records()
                        .getFirst()
                        .get("constraintsInstalled")
                        .asLong()
                );
                assertEquals(
                    15,
                    driver.executableQuery("CALL forward.schema.install()")
                        .execute()
                        .records()
                        .getFirst()
                        .get("constraintsInstalled")
                        .asLong()
                );
            }
        }
    }

    @Test
    void listsOnlyBundledNqeProjections() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build()) {
            try (var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())) {
                var records = driver.executableQuery(
                    "CALL forward.nqe.projections() YIELD name, version, sha256, enabled, requiredFacet RETURN *"
                )
                    .execute()
                    .records();

                assertEquals(4, records.size());
                assertTrue(records.stream().anyMatch(record -> "interfaces-v1".equals(record.get("name").asString())));
                assertTrue(records.stream().allMatch(record -> record.get("sha256").asString().length() == 64));
                assertEquals(2, records.stream().filter(record -> record.get("enabled").asBoolean()).count());
            }
        }
    }

    @Test
    void reportsFeaturesForNetworkAndSecurityTier() {
        System.setProperty("forward.licenseTier", "NS");
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build()) {
            try (var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())) {
                var records = driver.executableQuery("CALL forward.license.features()")
                    .execute().records();
                assertEquals(6, records.size());
                assertTrue(records.stream().allMatch(record -> record.get("enabled").asBoolean()));
                assertTrue(records.stream().allMatch(record -> "NS".equals(record.get("configuredLicenseTier").asString())));
            }
        }
    }

    @Test
    void batchedNqeImportScriptsCompile() throws IOException {
        List<String> scripts = List.of(
            "import-interfaces.cypher",
            "import-interface-addresses.cypher",
            "import-network-instances.cypher",
            "import-bgp-neighbors.cypher",
            "import-checks.cypher",
            "import-vulnerabilities.cypher",
            "import-paths.cypher"
        );
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build()) {
            try (
                var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI());
                var session = driver.session()
            ) {
                for (String script : scripts) {
                    String cypher = "EXPLAIN " + Files.readString(Path.of("demo", script));
                    session.run(
                        cypher,
                        Map.of(
                            "networkId", "network-1",
                            "snapshotId", "snapshot-1",
                            "flows", List.of(Map.of(
                                "name", "demo-flow",
                                "srcIp", "192.0.2.10",
                                "dstIp", "198.51.100.20"
                            )),
                            "pathOptions", Map.of("maxResults", 1)
                        )
                    ).consume();
                }
            }
        }
    }

    @Test
    void streamsFixedNqeAsynchronouslyAcrossResultPages() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expectedAuthorization = "Basic " + Base64.getEncoder()
            .encodeToString("demo-user:demo-password".getBytes(StandardCharsets.UTF_8));
        AtomicInteger resultRequests = new AtomicInteger();
        server.createContext("/api/networks/network-1/nqe-executions/exec-1/result", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals(expectedAuthorization, exchange.getRequestHeaders().getFirst("Authorization"));
            String query = exchange.getRequestURI().getQuery();
            resultRequests.incrementAndGet();
            if (query.contains("offset=0")) {
                respondJson(
                    exchange,
                    """
                    {"snapshotId":"snapshot-1","totalNumItems":3,"items":[
                      {"deviceName":"router-a","interfaceName":"Ethernet1","adminStatus":"UP"},
                      {"deviceName":"router-b","interfaceName":"Ethernet2","adminStatus":"DOWN"}
                    ]}
                    """
                );
            } else {
                assertTrue(query.contains("offset=2"));
                respondJson(
                    exchange,
                    """
                    {"snapshotId":"snapshot-1","totalNumItems":3,"items":[
                      {"deviceName":"router-c","interfaceName":"Ethernet3","adminStatus":"UP"}
                    ]}
                    """
                );
            }
        });
        server.createContext("/api/networks/network-1/nqe-executions/exec-1", exchange ->
            respond(
                exchange,
                expectedAuthorization,
                "GET",
                "{\"status\":\"COMPLETED\",\"outcome\":\"OK\",\"rowsProduced\":3}"
            )
        );
        server.createContext("/api/networks/network-1/nqe-executions", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(expectedAuthorization, exchange.getRequestHeaders().getFirst("Authorization"));
            var request = new ObjectMapper().readTree(exchange.getRequestBody());
            assertEquals(1, request.size());
            assertTrue(request.path("query").asText().contains("foreach interface in device.interfaces"));
            assertTrue(exchange.getRequestURI().getQuery().contains("snapshotId=snapshot-1"));
            respondJson(exchange, "{\"executionKey\":\"exec-1\",\"status\":\"SUBMITTED\"}");
        });
        server.start();

        System.setProperty("forward.url", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("forward.username", "demo-user");
        System.setProperty("forward.password", "demo-password");
        System.setProperty("forward.nqePollSeconds", "0");
        System.setProperty("forward.nqePageSize", "2");
        try (
            Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withProcedure(ForwardProcedures.class)
                .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())
        ) {
            var records = driver.executableQuery(
                """
                CALL forward.nqe.stream('interfaces-v1', 'network-1', 'snapshot-1')
                YIELD networkId, snapshotId, projection, projectionVersion, projectionSha256, executionKey, row
                RETURN *
                """
            ).execute().records();

            assertEquals(3, records.size());
            assertEquals(2, resultRequests.get());
            assertEquals("snapshot-1", records.getFirst().get("snapshotId").asString());
            assertEquals("interfaces-v1", records.getFirst().get("projection").asString());
            assertEquals("router-c", records.getLast().get("row").get("deviceName").asString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void importsLatestProcessedSnapshotWithReadOnlyRequests() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expectedAuthorization = "Basic " + Base64.getEncoder()
            .encodeToString("demo-user:demo-password".getBytes(StandardCharsets.UTF_8));
        server.createContext("/api/networks/network-1/snapshots", exchange ->
            respond(
                exchange,
                expectedAuthorization,
                """
                {"snapshots":[
                  {"id":"snapshot-old","state":"PROCESSED","processedAt":"2026-07-28T10:00:00Z"},
                  {"id":"snapshot-new","state":"PROCESSED","processedAt":"2026-07-29T10:00:00Z"}
                ]}
                """
            )
        );
        server.createContext("/api/networks/network-1/devices", exchange ->
            respond(
                exchange,
                expectedAuthorization,
                """
                [
                  {"name":"router-a","type":"ROUTER","vendor":"CISCO","managementIps":["10.0.0.1"]},
                  {"name":"router-b","type":"ROUTER","vendor":"JUNIPER","tags":["edge"]}
                ]
                """
            )
        );
        server.createContext("/api/snapshots/snapshot-new/topology", exchange ->
            respond(
                exchange,
                expectedAuthorization,
                """
                [
                  {"sourcePort":"router-a Ethernet1","targetPort":"router-b ge-0/0/1"},
                  {"sourcePort":"router-b ge-0/0/1","targetPort":"router-a Ethernet1"},
                  {"sourcePort":"unknown Ethernet9","targetPort":"router-a Ethernet2"}
                ]
                """
            )
        );
        server.start();

        System.setProperty("forward.url", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("forward.username", "demo-user");
        System.setProperty("forward.password", "demo-password");
        try (
            Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withProcedure(ForwardProcedures.class)
                .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())
        ) {
            var result = driver.executableQuery("CALL forward.import.snapshot('network-1', '')")
                .execute()
                .records()
                .getFirst();

            assertEquals("snapshot-new", result.get("snapshotId").asString());
            assertEquals(2, result.get("devices").asLong());
            assertEquals(1, result.get("links").asLong());
            assertEquals(1, result.get("skippedLinks").asLong());

            var counts = driver.executableQuery(
                """
                MATCH (device:ForwardDevice)
                OPTIONAL MATCH (:ForwardInterface)-[link:CONNECTED_TO]->(:ForwardInterface)
                RETURN count(DISTINCT device) AS devices, count(DISTINCT link) AS links
                """
            ).execute().records().getFirst();
            assertEquals(2, counts.get("devices").asLong());
            assertEquals(1, counts.get("links").asLong());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsChecksAndCheckDiagnosisFromReadOnlyEndpoints() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expectedAuthorization = basicAuthorization();
        server.createContext("/api/snapshots/snapshot-1/checks/check-1", exchange ->
            respond(
                exchange,
                expectedAuthorization,
                """
                {
                  "id":"check-1","name":"Edge isolation","status":"FAIL","numViolations":2,
                  "definition":{"checkType":"Isolation"},
                  "diagnosis":{"summary":"Synthetic diagnosis"}
                }
                """
            )
        );
        server.createContext("/api/snapshots/snapshot-1/checks", exchange ->
            respond(
                exchange,
                expectedAuthorization,
                """
                [{
                  "id":"check-1","name":"Edge isolation","status":"FAIL","priority":"HIGH",
                  "numViolations":2,"definition":{"checkType":"Isolation"}
                }]
                """
            )
        );
        server.start();
        configureForward(server);

        try (
            Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withProcedure(ForwardProcedures.class)
                .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())
        ) {
            var check = driver.executableQuery("CALL forward.checks.stream('network-1', 'snapshot-1')")
                .execute().records().getFirst();
            assertEquals("check-1", check.get("row").get("id").asString());
            assertEquals("FAIL", check.get("row").get("status").asString());

            var detail = driver.executableQuery(
                "CALL forward.checks.detail('check-1', 'network-1', 'snapshot-1')"
            ).execute().records().getFirst();
            assertEquals("Synthetic diagnosis", detail.get("row").get("diagnosis").get("summary").asString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsVulnerabilitySummariesAndSelectedDetails() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expectedAuthorization = basicAuthorization();
        server.createContext("/api/networks/network-1/vulnerabilities/CVE-2099-0001", exchange -> {
            assertTrue(exchange.getRequestURI().getQuery().contains("snapshotId=snapshot-1"));
            respond(
                exchange,
                expectedAuthorization,
                """
                {"id":"CVE-2099-0001","osInfos":[{"vendor":"EXAMPLE","os":"EXAMPLE_OS",
                  "devices":[{"deviceName":"router-a","result":"VULNERABLE"}]}]}
                """
            );
        });
        server.createContext("/api/networks/network-1/vulnerabilities", exchange -> {
            String requestQuery = exchange.getRequestURI().getQuery();
            assertTrue(requestQuery.contains("v=2"));
            assertTrue(requestQuery.contains("snapshotId=snapshot-1"));
            respond(
                exchange,
                expectedAuthorization,
                """
                {
                  "indexCreatedAt":"2026-01-01T00:00:00Z",
                  "indexUploadedAt":"2026-01-02T00:00:00Z",
                  "vulnerabilities":[{
                    "id":"CVE-2099-0001","description":"Synthetic CVE","hasCisaKevEntry":false,
                    "osInfos":[{"vendor":"EXAMPLE","os":"EXAMPLE_OS","severity":"HIGH","deviceCount":1,
                      "resultCounts":[{"result":"VULNERABLE","deviceCount":1}]}]
                  }]
                }
                """
            );
        });
        server.start();
        configureForward(server);

        try (
            Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withProcedure(ForwardProcedures.class)
                .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())
        ) {
            var summary = driver.executableQuery(
                "CALL forward.vulnerabilities.stream('network-1', 'snapshot-1')"
            ).execute().records().getFirst();
            assertEquals("CVE-2099-0001", summary.get("row").get("id").asString());
            assertEquals("2026-01-01T00:00:00Z", summary.get("indexCreatedAt").asString());

            var detail = driver.executableQuery(
                "CALL forward.vulnerabilities.detail('CVE-2099-0001', 'network-1', 'snapshot-1')"
            ).execute().records().getFirst();
            assertEquals("router-a", detail.get("row").get("osInfos").get(0).get("devices").get(0).get("deviceName").asString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesCuratedBulkPathOrderAndStripsLocalNames() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expectedAuthorization = basicAuthorization();
        server.createContext("/api/networks/network-1/paths-bulk", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(expectedAuthorization, exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(exchange.getRequestURI().getQuery().contains("snapshotId=snapshot-1"));
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            assertEquals(2, body.path("queries").size());
            assertFalse(body.path("queries").get(0).has("name"));
            assertEquals("198.51.100.20", body.path("queries").get(0).path("dstIp").asText());
            assertEquals(2, body.path("maxResults").asInt());
            respondJson(
                exchange,
                """
                [
                  {"srcIpLocationType":"HOST","dstIpLocationType":"HOST","timedOut":false,
                   "info":{"paths":[{"forwardingOutcome":"DELIVERED","securityOutcome":"PERMITTED",
                     "hops":[{"deviceName":"router-a"},{"deviceName":"router-b"}]}]}},
                  {"srcIpLocationType":"HOST","dstIpLocationType":"HOST","timedOut":false,
                   "info":{"paths":[{"forwardingOutcome":"DROPPED","securityOutcome":"DENIED",
                     "hops":[{"deviceName":"firewall-a"}]}]}}
                ]
                """
            );
        });
        server.start();
        configureForward(server);

        try (
            Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
                .withDisabledServer()
                .withProcedure(ForwardProcedures.class)
                .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())
        ) {
            List<Map<String, Object>> flows = List.of(
                Map.of("name", "checkout", "srcIp", "192.0.2.10", "dstIp", "198.51.100.20", "ipProto", 6L),
                Map.of("name", "admin", "from", "router-a", "dstIp", "203.0.113.30", "ipProto", 6L)
            );
            try (var session = driver.session()) {
                var records = session.run(
                    "CALL forward.paths.stream($flows, $options, 'network-1', 'snapshot-1')",
                    Map.of("flows", flows, "options", Map.of("maxResults", 2L))
                ).list();
                assertEquals(2, records.size());
                assertEquals("checkout", records.getFirst().get("name").asString());
                assertEquals("admin", records.getLast().get("name").asString());
                assertEquals("DROPPED", records.getLast().get("result").get("info").get("paths").get(0).get("forwardingOutcome").asString());
            }
        } finally {
            server.stop(0);
        }
    }

    private static String basicAuthorization() {
        return "Basic " + Base64.getEncoder()
            .encodeToString("demo-user:demo-password".getBytes(StandardCharsets.UTF_8));
    }

    private static void configureForward(HttpServer server) {
        System.setProperty("forward.url", "http://127.0.0.1:" + server.getAddress().getPort());
        System.setProperty("forward.username", "demo-user");
        System.setProperty("forward.password", "demo-password");
        System.setProperty("forward.licenseTier", "NS");
    }

    private static void respond(HttpExchange exchange, String expectedAuthorization, String body) throws IOException {
        respond(exchange, expectedAuthorization, "GET", body);
    }

    private static void respond(
        HttpExchange exchange,
        String expectedAuthorization,
        String expectedMethod,
        String body
    ) throws IOException {
        assertEquals(expectedMethod, exchange.getRequestMethod());
        assertEquals(expectedAuthorization, exchange.getRequestHeaders().getFirst("Authorization"));
        respondJson(exchange, body);
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
