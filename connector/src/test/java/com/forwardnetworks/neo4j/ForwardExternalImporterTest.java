package com.forwardnetworks.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.neo4j.harness.Neo4jBuilders;

class ForwardExternalImporterTest {

    @Test
    void importsAndPublishesThroughTheRemoteDriverWithoutServerProcedures() throws IOException {
        HttpServer server = startServer("""
            {"snapshots":[{"id":"snapshot-1","state":"PROCESSED","processedAt":"2026-07-30T10:00:00Z"}]}
            """);

        var config = new ForwardConfig(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "demo-user",
            "demo-password",
            "network-1",
            Duration.ofSeconds(10),
            Duration.ZERO,
            Duration.ofSeconds(10),
            100,
            ForwardLicenseTier.NS
        );
        try (
            var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())
        ) {
            var result = new ForwardExternalImporter(config, driver, "neo4j", 1)
                .importLatest("", "", true);
            assertEquals("snapshot-1", result.snapshotId());
            assertEquals(2, result.devices());
            assertEquals(1, result.links());
            assertEquals(7, result.components());
            assertTrue(result.published());

            var record = driver.executableQuery("""
                MATCH (network:ForwardNetwork {id: 'network-1'})
                MATCH (:ForwardSnapshot {id: 'snapshot-1'})-[:CONTAINS]->(device:ForwardDevice)
                OPTIONAL MATCH (:ForwardInterface)-[link:CONNECTED_TO]->(:ForwardInterface)
                OPTIONAL MATCH (address:ForwardIpAddress)
                OPTIONAL MATCH (neighbor:ForwardBgpNeighbor)
                OPTIONAL MATCH (check:ForwardCheckObservation)
                OPTIONAL MATCH (vulnerability:ForwardVulnerability)-[:HAS_OS_ANALYSIS]->(analysis:ForwardVulnerabilityOs)
                RETURN network.currentSnapshotId AS currentSnapshotId,
                       count(DISTINCT device) AS devices,
                       count(DISTINCT link) AS links,
                       count(DISTINCT address) AS addresses,
                       count(DISTINCT neighbor) AS neighbors,
                       count(DISTINCT check) AS checks,
                       count(DISTINCT vulnerability) AS vulnerabilities,
                       sum(DISTINCT analysis.vulnerableDeviceCount) AS vulnerableDevices
                """).execute().records().getFirst();
            assertEquals("snapshot-1", record.get("currentSnapshotId").asString());
            assertEquals(2, record.get("devices").asLong());
            assertEquals(1, record.get("links").asLong());
            assertEquals(1, record.get("addresses").asLong());
            assertEquals(1, record.get("neighbors").asLong());
            assertEquals(1, record.get("checks").asLong());
            assertEquals(1, record.get("vulnerabilities").asLong());
            assertEquals(1, record.get("vulnerableDevices").asLong());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer startServer(String snapshotsJson) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/networks/network-1/snapshots", exchange -> respond(exchange, snapshotsJson));
        server.createContext("/api/networks/network-1/devices", exchange -> respond(exchange, """
            [
              {"name":"router-a","type":"ROUTER","vendor":"CISCO","managementIps":["192.0.2.1"]},
              {"name":"router-b","type":"ROUTER","vendor":"JUNIPER"}
            ]
            """));
        server.createContext("/api/snapshots/snapshot-1/topology", exchange -> respond(exchange, """
            [{"sourcePort":"router-a Ethernet1","targetPort":"router-b ge-0/0/1"}]
            """));
        server.createContext("/api/snapshots/snapshot-1/checks", exchange -> respond(exchange, """
            [{"id":"check-1","name":"Edge redundancy","status":"PASS","definition":{"checkType":"Redundancy"}}]
            """));
        server.createContext("/api/networks/network-1/vulnerabilities", exchange -> respond(exchange, """
            {"indexCreatedAt":"2026-07-30T09:00:00Z","vulnerabilities":[{
              "id":"CVE-2099-0001","description":"Synthetic finding","hasCisaKevEntry":false,
              "osInfos":[{"vendor":"EXAMPLE","os":"EXAMPLE_OS","severity":"HIGH","v3Score":8.0,
                "resultCounts":[{"result":"VULNERABLE","deviceCount":1}],"deviceCount":1}]
            }]}
            """));
        AtomicInteger executions = new AtomicInteger();
        server.createContext("/api/networks/network-1/nqe-executions", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod())) {
                int execution = executions.incrementAndGet();
                respond(exchange,
                    "{\"executionKey\":\"exec-" + execution +
                    "\",\"status\":\"COMPLETED\",\"outcome\":\"OK\",\"rowsProduced\":1}"
                );
            } else if (path.endsWith("/exec-1/result")) {
                respond(exchange, """
                    {"snapshotId":"snapshot-1","totalNumItems":1,"items":[
                      {"deviceName":"router-a","interfaceName":"Ethernet1","adminStatus":"UP"}
                    ]}
                    """);
            } else if (path.endsWith("/exec-2/result")) {
                respond(exchange, """
                    {"snapshotId":"snapshot-1","totalNumItems":1,"items":[
                      {"deviceName":"router-a","interfaceName":"Ethernet1","subinterfaceName":"",
                       "sourceKind":"INTERFACE","ip":"192.0.2.1","prefixLength":24}
                    ]}
                    """);
            } else if (path.endsWith("/exec-3/result")) {
                respond(exchange, """
                    {"snapshotId":"snapshot-1","totalNumItems":1,"items":[
                      {"deviceName":"router-a","networkInstanceName":"default","instanceType":"VRF",
                       "interfaceCount":1,"vlanCount":0,"protocolCount":1,"ipv4RouteCount":2,"ipv6RouteCount":0}
                    ]}
                    """);
            } else if (path.endsWith("/exec-4/result")) {
                respond(exchange, """
                    {"snapshotId":"snapshot-1","totalNumItems":1,"items":[
                      {"deviceName":"router-a","networkInstanceName":"default","neighborAddress":"192.0.2.2",
                       "peerDeviceName":"router-b","peerVrf":"default","sessionState":"ESTABLISHED"}
                    ]}
                    """);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static ForwardConfig config(HttpServer server) {
        return new ForwardConfig(
            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
            "demo-user",
            "demo-password",
            "network-1",
            Duration.ofSeconds(10),
            Duration.ZERO,
            Duration.ofSeconds(10),
            100,
            ForwardLicenseTier.NS
        );
    }

    @Test
    void predictedSnapshotIsMarkedNeverCandidateAndRefusedForPublication() throws IOException {
        HttpServer server = startServer("""
            {"snapshots":[{"id":"snapshot-1","state":"PROCESSED","processedAt":"2026-07-30T10:00:00Z",
              "processingTrigger":"PREDICT","parentSnapshotId":"snapshot-0","changeSetId":"cs-1"}]}
            """);
        try (
            var neo4j = Neo4jBuilders.newInProcessBuilder().withDisabledServer().build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())
        ) {
            var importer = new ForwardExternalImporter(config(server), driver, "neo4j", 1);
            var refusal = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> importer.importLatest("", "snapshot-1", true)
            );
            assertTrue(refusal.getMessage().contains("Predict snapshot"), refusal.getMessage());
            assertTrue(
                driver.executableQuery("MATCH (s:ForwardSnapshot) RETURN count(s) AS n")
                    .execute().records().getFirst().get("n").asLong() == 0
            );

            var staged = importer.importLatest("", "snapshot-1", false);
            assertEquals(false, staged.published());
            var row = driver.executableQuery("""
                MATCH (s:ForwardSnapshot {id: 'snapshot-1'})
                MATCH (n:ForwardNetwork {id: 'network-1'})
                RETURN s.kind AS kind, s.parentSnapshotId AS parent, s.changeSetId AS changeSet,
                       n.candidateSnapshotId AS candidate, n.currentSnapshotId AS current
                """).execute().records().getFirst();
            assertEquals("PREDICTED", row.get("kind").asString());
            assertEquals("snapshot-0", row.get("parent").asString());
            assertEquals("cs-1", row.get("changeSet").asString());
            assertTrue(row.get("candidate").isNull());
            assertTrue(row.get("current").isNull());

            // Forward's snapshot list is empty now, so the kind is unknown; the stored marker must still block publish.
            HttpServer unknownKind = startServer("{\"snapshots\":[]}");
            try {
                var blind = new ForwardExternalImporter(config(unknownKind), driver, "neo4j", 1);
                var silent = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> blind.importLatest("", "snapshot-1", true)
                );
                assertTrue(silent.getMessage().contains("was not published"), silent.getMessage());
                assertTrue(
                    driver.executableQuery("MATCH (n:ForwardNetwork {id: 'network-1'}) RETURN n.currentSnapshotId AS c")
                        .execute().records().getFirst().get("c").isNull()
                );
            } finally {
                unknownKind.stop(0);
            }
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
