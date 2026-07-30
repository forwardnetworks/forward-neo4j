package com.forwardnetworks.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                    20,
                    driver.executableQuery("CALL forward.schema.install()")
                        .execute()
                        .records()
                        .getFirst()
                        .get("constraintsInstalled")
                        .asLong()
                );
                assertEquals(
                    20,
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
    void previewsAndPrunesOnlyExplicitUnprotectedManagedSnapshots() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build()) {
            try (var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())) {
                driver.executableQuery(
                    """
                    CREATE (network:ForwardNetwork {
                      id: 'network-1', currentSnapshotId: 'snapshot-current',
                      previousSnapshotId: 'snapshot-previous', candidateSnapshotId: 'snapshot-candidate'
                    })
                    CREATE (old:ForwardSnapshot {
                      networkId: 'network-1', id: 'snapshot-old', publicationState: 'SUPERSEDED',
                      importedAt: datetime('2025-01-01T00:00:00Z'), publishedAt: datetime('2025-01-01T01:00:00Z')
                    })
                    CREATE (previous:ForwardSnapshot {
                      networkId: 'network-1', id: 'snapshot-previous', publicationState: 'SUPERSEDED',
                      importedAt: datetime('2025-02-01T00:00:00Z'), publishedAt: datetime('2025-02-01T01:00:00Z')
                    })
                    CREATE (current:ForwardSnapshot {
                      networkId: 'network-1', id: 'snapshot-current', publicationState: 'PUBLISHED',
                      importedAt: datetime('2025-03-01T00:00:00Z'), publishedAt: datetime('2025-03-01T01:00:00Z')
                    })
                    CREATE (candidate:ForwardSnapshot {
                      networkId: 'network-1', id: 'snapshot-candidate', publicationState: 'STAGED',
                      importedAt: datetime('2025-04-01T00:00:00Z')
                    })
                    CREATE (network)-[:HAS_SNAPSHOT]->(old),
                           (network)-[:HAS_SNAPSHOT]->(previous),
                           (network)-[:HAS_SNAPSHOT]->(current),
                           (network)-[:HAS_SNAPSHOT]->(candidate)
                    CREATE (old)-[:CONTAINS]->(:ForwardDevice {
                      networkId: 'network-1', snapshotId: 'snapshot-old', id: 'old-router'
                    })
                    CREATE (:ForwardImportJob {
                      networkId: 'network-1', snapshotId: 'snapshot-old', component: 'inventory'
                    })
                    CREATE (:CustomerOwned {
                      networkId: 'network-1', snapshotId: 'snapshot-old', id: 'must-survive'
                    })
                    CREATE (:ForwardApplication {networkId: 'network-1', id: 'app-1'})
                    """
                ).execute();

                var preview = driver.executableQuery(
                    "CALL forward.snapshot.retention.preview('network-1', 2, 0)"
                ).execute().records();
                assertEquals(4, preview.size());
                var old = preview.stream()
                    .filter(record -> "snapshot-old".equals(record.get("snapshotId").asString()))
                    .findFirst()
                    .orElseThrow();
                assertTrue(old.get("eligible").asBoolean());
                assertEquals("ELIGIBLE", old.get("reason").asString());
                assertTrue(preview.stream()
                    .filter(record -> !"snapshot-old".equals(record.get("snapshotId").asString()))
                    .allMatch(record -> record.get("protectedSnapshot").asBoolean()));

                assertThrows(Exception.class, () -> driver.executableQuery(
                    "CALL forward.snapshot.prune('network-1', ['snapshot-current'])"
                ).execute());

                var prune = driver.executableQuery(
                    "CALL forward.snapshot.prune('network-1', ['snapshot-old'])"
                ).execute().records().getFirst();
                assertEquals(1, prune.get("snapshotsDeleted").asLong());
                assertEquals(3, prune.get("nodesDeleted").asLong());

                var remaining = driver.executableQuery(
                    """
                    MATCH (custom:CustomerOwned {id: 'must-survive'})
                    MATCH (app:ForwardApplication {id: 'app-1'})
                    RETURN count(custom) AS custom, count(app) AS applications
                    """
                ).execute().records().getFirst();
                assertEquals(1, remaining.get("custom").asLong());
                assertEquals(1, remaining.get("applications").asLong());
            }
        }
    }

    @Test
    void exportsPrometheusHealthMetricsFromDurableGraphState() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build()) {
            try (var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())) {
                driver.executableQuery(
                    """
                    CREATE (network:ForwardNetwork {
                      id: 'network-1', currentSnapshotId: 'snapshot-1', candidateSnapshotId: 'snapshot-2'
                    })
                    CREATE (current:ForwardSnapshot {
                      networkId: 'network-1', id: 'snapshot-1', publicationState: 'PUBLISHED',
                      publishedAt: datetime()
                    })
                    CREATE (candidate:ForwardSnapshot {
                      networkId: 'network-1', id: 'snapshot-2', publicationState: 'STAGED'
                    })
                    CREATE (network)-[:HAS_SNAPSHOT]->(current),
                           (network)-[:HAS_SNAPSHOT]->(candidate)
                    CREATE (current)-[:CONTAINS]->(:ForwardDevice {
                      networkId: 'network-1', snapshotId: 'snapshot-1', id: 'router-1'
                    })
                    CREATE (:ForwardImportJob {
                      networkId: 'network-1', snapshotId: 'snapshot-2', component: 'inventory', state: 'IMPORTING'
                    })
                    """
                ).execute();

                List<String> lines = driver.executableQuery(
                    "CALL forward.metrics.prometheus('network-1') YIELD line RETURN line"
                ).execute().records().stream().map(record -> record.get("line").asString()).toList();
                assertTrue(lines.contains("# TYPE forward_neo4j_devices_current gauge"));
                assertTrue(lines.contains("forward_neo4j_devices_current{network_id=\"network-1\"} 1"));
                assertTrue(lines.contains("forward_neo4j_candidate_pending{network_id=\"network-1\"} 1"));
                assertTrue(lines.contains(
                    "forward_neo4j_import_jobs_total{network_id=\"network-1\",state=\"IMPORTING\"} 1"
                ));
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
            "import-inventory-devices.cypher",
            "import-inventory-topology.cypher",
            "import-interfaces.cypher",
            "import-interface-addresses.cypher",
            "import-network-instances.cypher",
            "import-bgp-neighbors.cypher",
            "import-checks.cypher",
            "import-vulnerabilities.cypher",
            "import-vulnerability-details.cypher",
            "import-check-details.cypher",
            "import-paths.cypher",
            "import-business-context.cypher"
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
                            "pathOptions", Map.of("maxResults", 1),
                            "cveIds", List.of("CVE-2099-0001"),
                            "checkIds", List.of("check-1"),
                            "applications", List.of(Map.of(
                                "id", "sample-app",
                                "name", "Sample Application",
                                "deviceNames", List.of("router-a"),
                                "flows", List.of()
                            ))
                        )
                    ).consume();
                }
            }
        }
    }

    @Test
    void customerDemoQueriesCompile() throws IOException {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI());
            var session = driver.session()) {
            String script = Files.readString(Path.of("demo", "customer-demo.cypher"));
            for (String statement : script.split(";\\s*(?:\\R|$)")) {
                if (!statement.isBlank()) {
                    session.run("EXPLAIN " + statement, Map.of("networkId", "network-1")).consume();
                }
            }
        }
    }

    @Test
    void snapshotDiffQueriesCompile() throws IOException {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI());
            var session = driver.session()) {
            String script = Files.readString(Path.of("demo", "snapshot-diff.cypher"));
            for (String statement : script.split(";\\s*(?:\\R|$)")) {
                if (!statement.isBlank()) {
                    session.run("EXPLAIN " + statement, Map.of("networkId", "network-1")).consume();
                }
            }
        }
    }

    @Test
    void validatesComponentsBeforeAtomicallyPublishingSnapshot() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())) {
            driver.executableQuery("CALL forward.schema.install()").execute();
            driver.executableQuery(
                """
                MERGE (network:ForwardNetwork {id: 'network-1'})
                SET network.candidateSnapshotId = 'snapshot-1'
                MERGE (snapshot:ForwardSnapshot {networkId: 'network-1', id: 'snapshot-1'})
                SET snapshot.publicationState = 'STAGED', snapshot.validationState = 'PENDING',
                    snapshot.importedAt = datetime()
                MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
                MERGE (device:ForwardDevice {networkId: 'network-1', snapshotId: 'snapshot-1', id: 'router-a'})
                MERGE (snapshot)-[:CONTAINS]->(device)
                MERGE (job:ForwardImportJob {
                  networkId: 'network-1', snapshotId: 'snapshot-1', component: 'inventory'
                })
                SET job.state = 'VALIDATED', job.attempt = 1, job.rowsImported = 1
                MERGE (snapshot)-[:HAS_IMPORT_JOB]->(job)
                """
            ).execute();

            var submitted = driver.executableQuery(
                "CALL forward.import.submit('interfaces-v1', 'network-1', 'snapshot-1')"
            ).execute().records().getFirst();
            assertEquals("SUBMITTED", submitted.get("state").asString());

            var started = driver.executableQuery(
                "CALL forward.import.begin('interfaces-v1', 'network-1', 'snapshot-1')"
            ).execute().records().getFirst();
            assertEquals("IMPORTING", started.get("state").asString());

            var completed = driver.executableQuery(
                "CALL forward.import.complete('interfaces-v1', 'network-1', 'snapshot-1', 2, 2)"
            ).execute().records().getFirst();
            assertEquals("VALIDATED", completed.get("state").asString());

            var validation = driver.executableQuery(
                "CALL forward.snapshot.validate('network-1', 'snapshot-1', ['interfaces-v1'])"
            ).execute().records().getFirst();
            assertEquals("VALIDATED", validation.get("state").asString());
            assertTrue(validation.get("errors").asList().isEmpty());

            var publication = driver.executableQuery(
                "CALL forward.snapshot.publish('network-1', 'snapshot-1')"
            ).execute().records().getFirst();
            assertEquals("snapshot-1", publication.get("snapshotId").asString());
            var repeatedPublication = driver.executableQuery(
                "CALL forward.snapshot.publish('network-1', 'snapshot-1')"
            ).execute().records().getFirst();
            assertEquals("snapshot-1", repeatedPublication.get("snapshotId").asString());

            var status = driver.executableQuery(
                "CALL forward.snapshot.status('network-1', 'snapshot-1')"
            ).execute().records().getFirst();
            assertEquals("PUBLISHED", status.get("publicationState").asString());
            assertEquals("snapshot-1", status.get("currentSnapshotId").asString());
            assertTrue(status.get("candidateSnapshotId").isNull());
            assertThrows(
                Exception.class,
                () -> driver.executableQuery(
                    "CALL forward.import.begin('interfaces-v1', 'network-1', 'snapshot-1')"
                ).execute()
            );
        }
    }

    @Test
    void rowCountMismatchBlocksSnapshotValidation() {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI())) {
            driver.executableQuery(
                """
                CREATE (network:ForwardNetwork {id: 'network-1', candidateSnapshotId: 'snapshot-1'})
                CREATE (snapshot:ForwardSnapshot {
                  networkId: 'network-1', id: 'snapshot-1', publicationState: 'STAGED', validationState: 'PENDING'
                })
                CREATE (network)-[:HAS_SNAPSHOT]->(snapshot)
                CREATE (device:ForwardDevice {networkId: 'network-1', snapshotId: 'snapshot-1', id: 'router-a'})
                CREATE (snapshot)-[:CONTAINS]->(device)
                CREATE (inventory:ForwardImportJob {
                  networkId: 'network-1', snapshotId: 'snapshot-1', component: 'inventory',
                  state: 'VALIDATED', attempt: 1
                })
                CREATE (snapshot)-[:HAS_IMPORT_JOB]->(inventory)
                """
            ).execute();

            driver.executableQuery(
                "CALL forward.import.begin('interface-addresses-v1', 'network-1', 'snapshot-1')"
            ).execute();
            var failed = driver.executableQuery(
                "CALL forward.import.complete('interface-addresses-v1', 'network-1', 'snapshot-1', 3, 4)"
            ).execute().records().getFirst();
            assertEquals("FAILED", failed.get("state").asString());

            var validation = driver.executableQuery(
                "CALL forward.snapshot.validate('network-1', 'snapshot-1', ['interface-addresses-v1'])"
            ).execute().records().getFirst();
            assertEquals("INVALID", validation.get("state").asString());
            assertEquals(List.of("interface-addresses-v1"), validation.get("missingComponents").asList(value -> value.asString()));
            assertThrows(
                Exception.class,
                () -> driver.executableQuery(
                    "CALL forward.snapshot.publish('network-1', 'snapshot-1')"
                ).execute()
            );
        }
    }

    @Test
    void syntheticDatasetExercisesPublishedCrossDomainGraph() throws IOException {
        System.setProperty("forward.licenseTier", "NS");
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI());
            var session = driver.session()) {
            String script = Files.readString(Path.of("demo", "synthetic-data.cypher"));
            for (String statement : script.split(";\\s*(?:\\R|$)")) {
                if (!statement.isBlank()) {
                    session.run(statement).consume();
                }
            }

            var status = session.run(
                "CALL forward.snapshot.status('demo-network', 'demo-snapshot')"
            ).single();
            assertEquals("PUBLISHED", status.get("publicationState").asString());
            assertEquals("VALIDATED", status.get("validationState").asString());

            var crossDomain = session.run(
                """
                MATCH (:ForwardApplication)-[:RUNS_ON]->(device:ForwardDevice)
                OPTIONAL MATCH (:ForwardVulnerability)-[:AFFECTS]->(device)
                OPTIONAL MATCH (:ForwardApplicationFlow)-[:EVALUATED_AS]->(:ForwardPathObservation)
                  -[:HAS_PATH]->(:ForwardPathResult)-[:HAS_HOP]->(hop:ForwardPathHop)
                OPTIONAL MATCH (:ForwardPathHop)-[next:NEXT_HOP]->(:ForwardPathHop)
                RETURN count(DISTINCT device) AS applicationDevices,
                       count(DISTINCT hop) AS pathHops,
                       count(DISTINCT next) AS nextHopRelationships
                """
            ).single();
            assertEquals(2, crossDomain.get("applicationDevices").asLong());
            assertEquals(2, crossDomain.get("pathHops").asLong());
            assertEquals(1, crossDomain.get("nextHopRelationships").asLong());
        }
    }

    @Test
    void businessContextRejectsUnknownDevicesWithoutCreatingInventory() throws IOException {
        try (Neo4j neo4j = Neo4jBuilders.newInProcessBuilder()
            .withDisabledServer()
            .withProcedure(ForwardProcedures.class)
            .build();
            var driver = org.neo4j.driver.GraphDatabase.driver(neo4j.boltURI());
            var session = driver.session()) {
            session.run(
                """
                CREATE (network:ForwardNetwork {id: 'network-1', candidateSnapshotId: 'snapshot-1'})
                CREATE (snapshot:ForwardSnapshot {
                  networkId: 'network-1', id: 'snapshot-1', publicationState: 'STAGED', validationState: 'PENDING'
                })
                CREATE (network)-[:HAS_SNAPSHOT]->(snapshot)
                CREATE (device:ForwardDevice {networkId: 'network-1', snapshotId: 'snapshot-1', id: 'router-a'})
                CREATE (snapshot)-[:CONTAINS]->(device)
                """
            ).consume();
            String importer = Files.readString(Path.of("demo", "import-business-context.cypher"));
            Map<String, Object> baseParameters = Map.of(
                "networkId", "network-1",
                "snapshotId", "snapshot-1",
                "applications", List.of(Map.of(
                    "id", "app-1",
                    "name", "Application One",
                    "deviceNames", List.of("missing-device"),
                    "flows", List.of()
                ))
            );
            assertTrue(session.run(importer, baseParameters).list().isEmpty());

            var rejected = session.run(
                """
                MATCH (job:ForwardImportJob {component: 'business-context'})
                OPTIONAL MATCH (placeholder:ForwardDevice {id: 'missing-device'})
                RETURN job.state AS state, count(placeholder) AS placeholders
                """
            ).single();
            assertEquals("IMPORTING", rejected.get("state").asString());
            assertEquals(0, rejected.get("placeholders").asLong());

            Map<String, Object> validParameters = Map.of(
                "networkId", "network-1",
                "snapshotId", "snapshot-1",
                "applications", List.of(Map.of(
                    "id", "app-1",
                    "name", "Application One",
                    "deviceNames", List.of("router-a"),
                    "flows", List.of(Map.of(
                        "name", "app-flow",
                        "srcIp", "192.0.2.10",
                        "dstIp", "198.51.100.20"
                    ))
                ))
            );
            var imported = session.run(importer, validParameters).single();
            assertEquals("VALIDATED", imported.get("state").asString());
            assertEquals(1, imported.get("importedRows").asLong());
            assertEquals(1, session.run(
                "MATCH (:ForwardApplication)-[deployment:RUNS_ON]->(:ForwardDevice) RETURN count(deployment) AS count"
            ).single().get("count").asLong());
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
    void emitsProvenanceEnvelopeForZeroRowNqe() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expectedAuthorization = basicAuthorization();
        server.createContext("/api/networks/network-1/nqe-executions/exec-empty/result", exchange ->
            respond(
                exchange,
                expectedAuthorization,
                "GET",
                "{\"snapshotId\":\"snapshot-1\",\"totalNumItems\":0,\"items\":[]}"
            )
        );
        server.createContext("/api/networks/network-1/nqe-executions", exchange ->
            respond(
                exchange,
                expectedAuthorization,
                "POST",
                "{\"executionKey\":\"exec-empty\",\"status\":\"COMPLETED\",\"outcome\":\"OK\",\"rowsProduced\":0}"
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
            var records = driver.executableQuery(
                """
                CALL forward.nqe.stream('interfaces-v1', 'network-1', 'snapshot-1')
                YIELD projection, projectionVersion, projectionSha256, executionKey, resultRow, row
                RETURN *
                """
            ).execute().records();

            assertEquals(1, records.size());
            assertFalse(records.getFirst().get("resultRow").asBoolean());
            assertTrue(records.getFirst().get("row").isNull());
            assertEquals("exec-empty", records.getFirst().get("executionKey").asString());
            assertEquals(64, records.getFirst().get("projectionSha256").asString().length());
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

            driver.executableQuery("CALL forward.inventory.begin('network-1', 'snapshot-new')")
                .execute();
            try (var session = driver.session()) {
                var parameters = Map.<String, Object>of(
                    "networkId", "network-1",
                    "snapshotId", "snapshot-new"
                );
                var devices = session.run(
                    Files.readString(Path.of("demo", "import-inventory-devices.cypher")),
                    parameters
                ).single();
                assertEquals("INVENTORY_DEVICES=2", devices.get("marker").asString());

                var topology = session.run(
                    Files.readString(Path.of("demo", "import-inventory-topology.cypher")),
                    parameters
                ).single();
                assertEquals("INVENTORY_TOPOLOGY=2|1", topology.get("marker").asString());
            }
            var streamed = driver.executableQuery(
                "CALL forward.inventory.complete('network-1', 'snapshot-new', 1)"
            ).execute().records().getFirst();
            assertEquals(2, streamed.get("devices").asLong());
            assertEquals(1, streamed.get("links").asLong());
            assertEquals(1, streamed.get("skippedLinks").asLong());

            driver.executableQuery("CALL forward.snapshot.validate('network-1', 'snapshot-new', [])").execute();
            driver.executableQuery("CALL forward.snapshot.publish('network-1', 'snapshot-new')").execute();
            var noOp = driver.executableQuery("CALL forward.inventory.begin('network-1', 'snapshot-new')")
                .execute().records().getFirst();
            assertTrue(noOp.get("alreadyPublished").asBoolean());
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
