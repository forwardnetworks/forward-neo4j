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
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/networks/network-1/snapshots", exchange -> respond(exchange, """
            {"snapshots":[{"id":"snapshot-1","state":"PROCESSED","processedAt":"2026-07-30T10:00:00Z"}]}
            """));
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

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
