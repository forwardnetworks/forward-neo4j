package com.forwardnetworks.neo4j;

import com.forwardnetworks.neo4j.ForwardApiClient.DeviceData;
import com.forwardnetworks.neo4j.ForwardApiClient.TopologyLinkData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;

final class ForwardExternalImporter {

    private static final List<String> SCHEMA = List.of(
        "CREATE CONSTRAINT forward_network IF NOT EXISTS FOR (n:ForwardNetwork) REQUIRE n.id IS UNIQUE",
        "CREATE CONSTRAINT forward_snapshot IF NOT EXISTS FOR (n:ForwardSnapshot) REQUIRE (n.networkId, n.id) IS UNIQUE",
        "CREATE CONSTRAINT forward_device IF NOT EXISTS FOR (n:ForwardDevice) REQUIRE (n.networkId, n.snapshotId, n.id) IS UNIQUE",
        "CREATE CONSTRAINT forward_interface IF NOT EXISTS FOR (n:ForwardInterface) REQUIRE (n.networkId, n.snapshotId, n.id) IS UNIQUE",
        "CREATE CONSTRAINT forward_subinterface IF NOT EXISTS FOR (n:ForwardSubinterface) REQUIRE (n.networkId, n.snapshotId, n.deviceName, n.interfaceName, n.name) IS UNIQUE",
        "CREATE CONSTRAINT forward_ip_address IF NOT EXISTS FOR (n:ForwardIpAddress) REQUIRE (n.networkId, n.snapshotId, n.deviceName, n.interfaceName, n.subinterfaceName, n.sourceKind, n.ip, n.prefixLength) IS UNIQUE",
        "CREATE CONSTRAINT forward_network_instance IF NOT EXISTS FOR (n:ForwardNetworkInstance) REQUIRE (n.networkId, n.snapshotId, n.deviceName, n.name) IS UNIQUE",
        "CREATE CONSTRAINT forward_bgp_neighbor IF NOT EXISTS FOR (n:ForwardBgpNeighbor) REQUIRE (n.networkId, n.snapshotId, n.deviceName, n.networkInstanceName, n.neighborAddress) IS UNIQUE",
        "CREATE CONSTRAINT forward_projection_run IF NOT EXISTS FOR (n:ForwardProjectionRun) REQUIRE (n.networkId, n.snapshotId, n.name, n.version, n.sha256) IS UNIQUE",
        "CREATE CONSTRAINT forward_import_job IF NOT EXISTS FOR (n:ForwardImportJob) REQUIRE (n.networkId, n.snapshotId, n.component) IS UNIQUE",
        "CREATE CONSTRAINT forward_check_observation IF NOT EXISTS FOR (n:ForwardCheckObservation) REQUIRE (n.networkId, n.snapshotId, n.id) IS UNIQUE",
        "CREATE CONSTRAINT forward_vulnerability IF NOT EXISTS FOR (n:ForwardVulnerability) REQUIRE (n.networkId, n.snapshotId, n.id) IS UNIQUE",
        "CREATE CONSTRAINT forward_vulnerability_os IF NOT EXISTS FOR (n:ForwardVulnerabilityOs) REQUIRE (n.networkId, n.snapshotId, n.cveId, n.vendor, n.os) IS UNIQUE"
    );

    private static final String DEVICE_QUERY = """
        MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
        UNWIND $rows AS row
        MERGE (device:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.id})
        SET device += row.properties, device.lastImportedAt = datetime()
        MERGE (snapshot)-[:CONTAINS]->(device)
        """;
    private static final String TOPOLOGY_QUERY = """
        UNWIND $rows AS row
        MATCH (sourceDevice:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.sourceDevice})
        MATCH (targetDevice:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.targetDevice})
        MERGE (source:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: row.sourceId})
        SET source.name = row.sourceInterface, source.deviceName = row.sourceDevice, source.lastImportedAt = datetime()
        MERGE (target:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: row.targetId})
        SET target.name = row.targetInterface, target.deviceName = row.targetDevice, target.lastImportedAt = datetime()
        MERGE (sourceDevice)-[:HAS_INTERFACE]->(source)
        MERGE (targetDevice)-[:HAS_INTERFACE]->(target)
        MERGE (source)-[:CONNECTED_TO]->(target)
        """;
    private static final String INTERFACES_QUERY = """
        UNWIND $rows AS row
        MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
        MERGE (device:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.deviceName})
        MERGE (snapshot)-[:CONTAINS]->(device)
        MERGE (interface:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: row.deviceName + ' ' + row.interfaceName})
        SET interface.deviceName = row.deviceName, interface.name = row.interfaceName,
            interface.interfaceType = row.interfaceType, interface.layer = row.layer, interface.mtu = row.mtu,
            interface.loopbackMode = row.loopbackMode, interface.description = row.description,
            interface.adminStatus = row.adminStatus, interface.operStatus = row.operStatus,
            interface.speedMbps = row.speedMbps, interface.aggregateId = row.aggregateId
        MERGE (device)-[:HAS_INTERFACE]->(interface)
        """;
    private static final String ADDRESSES_QUERY = """
        UNWIND $rows AS row
        MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
        MERGE (device:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.deviceName})
        MERGE (snapshot)-[:CONTAINS]->(device)
        MERGE (interface:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: row.deviceName + ' ' + row.interfaceName})
        SET interface.deviceName = row.deviceName, interface.name = row.interfaceName
        MERGE (device)-[:HAS_INTERFACE]->(interface)
        MERGE (address:ForwardIpAddress {networkId: $networkId, snapshotId: $snapshotId,
          deviceName: row.deviceName, interfaceName: row.interfaceName,
          subinterfaceName: row.subinterfaceName, sourceKind: row.sourceKind,
          ip: row.ip, prefixLength: row.prefixLength})
        SET address.networkInstanceName = row.networkInstanceName
        FOREACH (_ IN CASE WHEN row.sourceKind = 'SUBINTERFACE' THEN [1] ELSE [] END |
          MERGE (subinterface:ForwardSubinterface {networkId: $networkId, snapshotId: $snapshotId,
            deviceName: row.deviceName, interfaceName: row.interfaceName, name: row.subinterfaceName})
          MERGE (interface)-[:HAS_SUBINTERFACE]->(subinterface)
          MERGE (subinterface)-[:HAS_IP_ADDRESS]->(address))
        FOREACH (_ IN CASE WHEN row.sourceKind <> 'SUBINTERFACE' THEN [1] ELSE [] END |
          MERGE (interface)-[:HAS_IP_ADDRESS]->(address))
        FOREACH (_ IN CASE WHEN row.networkInstanceName IS NOT NULL THEN [1] ELSE [] END |
          MERGE (instance:ForwardNetworkInstance {networkId: $networkId, snapshotId: $snapshotId,
            deviceName: row.deviceName, name: row.networkInstanceName})
          MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
          MERGE (address)-[:IN_NETWORK_INSTANCE]->(instance))
        """;
    private static final String INSTANCES_QUERY = """
        UNWIND $rows AS row
        MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
        MERGE (device:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.deviceName})
        MERGE (snapshot)-[:CONTAINS]->(device)
        MERGE (instance:ForwardNetworkInstance {networkId: $networkId, snapshotId: $snapshotId,
          deviceName: row.deviceName, name: row.networkInstanceName})
        SET instance.instanceType = row.instanceType, instance.interfaceCount = row.interfaceCount,
            instance.vlanCount = row.vlanCount, instance.protocolCount = row.protocolCount,
            instance.ipv4RouteCount = row.ipv4RouteCount, instance.ipv6RouteCount = row.ipv6RouteCount
        MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
        """;
    private static final String BGP_QUERY = """
        UNWIND $rows AS row
        MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
        MERGE (device:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.deviceName})
        MERGE (snapshot)-[:CONTAINS]->(device)
        MERGE (instance:ForwardNetworkInstance {networkId: $networkId, snapshotId: $snapshotId,
          deviceName: row.deviceName, name: row.networkInstanceName})
        MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
        MERGE (neighbor:ForwardBgpNeighbor {networkId: $networkId, snapshotId: $snapshotId,
          deviceName: row.deviceName, networkInstanceName: row.networkInstanceName,
          neighborAddress: row.neighborAddress})
        SET neighbor += row
        REMOVE neighbor.deviceName, neighbor.networkInstanceName, neighbor.neighborAddress
        SET neighbor.deviceName = row.deviceName, neighbor.networkInstanceName = row.networkInstanceName,
            neighbor.neighborAddress = row.neighborAddress
        MERGE (instance)-[:HAS_BGP_NEIGHBOR]->(neighbor)
        FOREACH (_ IN CASE WHEN row.peerDeviceName IS NOT NULL THEN [1] ELSE [] END |
          MERGE (peer:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.peerDeviceName})
          MERGE (snapshot)-[:CONTAINS]->(peer)
          MERGE (neighbor)-[:RESOLVES_TO_DEVICE]->(peer))
        FOREACH (_ IN CASE WHEN row.peerDeviceName IS NOT NULL AND row.peerVrf IS NOT NULL THEN [1] ELSE [] END |
          MERGE (peer:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: row.peerDeviceName})
          MERGE (peerInstance:ForwardNetworkInstance {networkId: $networkId, snapshotId: $snapshotId,
            deviceName: row.peerDeviceName, name: row.peerVrf})
          MERGE (peer)-[:HAS_NETWORK_INSTANCE]->(peerInstance)
          MERGE (neighbor)-[:RESOLVES_TO_NETWORK_INSTANCE]->(peerInstance))
        """;
    private static final String CHECKS_QUERY = """
        UNWIND $rows AS row
        MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
        MERGE (check:ForwardCheckObservation {networkId: $networkId, snapshotId: $snapshotId, id: row.id})
        SET check.name = row.name, check.description = row.description, check.note = row.note,
            check.enabled = row.enabled, check.priority = row.priority, check.status = row.status,
            check.numViolations = row.numViolations, check.executionDurationMillis = row.executionDurationMillis,
            check.createdAt = row.createdAt, check.definedAt = row.definedAt, check.editedAt = row.editedAt,
            check.executedAt = row.executedAt, check.tags = row.tags,
            check.checkType = row.definition.checkType, check.lastImportedAt = datetime()
        MERGE (snapshot)-[:HAS_CHECK]->(check)
        """;
    private static final String VULNERABILITIES_QUERY = """
        UNWIND $rows AS row
        MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
        MERGE (vulnerability:ForwardVulnerability {networkId: $networkId, snapshotId: $snapshotId, id: row.id})
        SET vulnerability.description = row.description, vulnerability.hasCisaKevEntry = row.hasCisaKevEntry,
            vulnerability.weaknesses = row.weaknesses, vulnerability.lastImportedAt = datetime()
        MERGE (snapshot)-[:HAS_VULNERABILITY]->(vulnerability)
        WITH vulnerability, coalesce(row.osInfos, []) AS osInfos
        UNWIND osInfos AS osInfo
        MERGE (analysis:ForwardVulnerabilityOs {networkId: $networkId, snapshotId: $snapshotId,
          cveId: vulnerability.id, vendor: osInfo.vendor, os: osInfo.os})
        SET analysis.severity = osInfo.severity,
            analysis.v2Score = osInfo.v2Score,
            analysis.v3Score = osInfo.v3Score,
            analysis.v4Score = osInfo.v4Score,
            analysis.url = osInfo.url,
            analysis.publishDate = osInfo.publishDate,
            analysis.advisoryMentionsExploit = osInfo.advisoryMentionsExploit,
            analysis.configDependent = osInfo.configDependent,
            analysis.configAnalysis = osInfo.configAnalysis,
            analysis.osVersions = osInfo.osVersions,
            analysis.deviceCount = osInfo.deviceCount,
            analysis.vulnerableDeviceCount = reduce(total = 0, item IN coalesce(osInfo.resultCounts, []) |
              total + CASE WHEN item.result IN ['VULNERABLE', 'OS_VULNERABLE'] THEN coalesce(item.deviceCount, 0) ELSE 0 END),
            analysis.unconfirmedDeviceCount = reduce(total = 0, item IN coalesce(osInfo.resultCounts, []) |
              total + CASE WHEN item.result IN ['UNCONFIRMED', 'UNIMPLEMENTED'] THEN coalesce(item.deviceCount, 0) ELSE 0 END),
            analysis.notVulnerableDeviceCount = reduce(total = 0, item IN coalesce(osInfo.resultCounts, []) |
              total + CASE WHEN item.result = 'NOT_VULNERABLE' THEN coalesce(item.deviceCount, 0) ELSE 0 END),
            analysis.locationIds = osInfo.locationIds,
            analysis.tags = osInfo.tags,
            analysis.lastImportedAt = datetime()
        MERGE (vulnerability)-[:HAS_OS_ANALYSIS]->(analysis)
        """;

    private final ForwardConfig config;
    private final ForwardApiClient client;
    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final int batchSize;

    ForwardExternalImporter(ForwardConfig config, Driver driver, String database, int batchSize) {
        this.config = config;
        this.client = new ForwardApiClient(config);
        this.driver = driver;
        this.sessionConfig = SessionConfig.forDatabase(database);
        this.batchSize = batchSize;
    }

    ImportSummary importLatest(String requestedNetworkId, String requestedSnapshotId, boolean publish) {
        String networkId = config.resolveNetworkId(requestedNetworkId);
        var snapshot = client.selectSnapshot(networkId, requestedSnapshotId);
        installSchema();
        stage(networkId, snapshot);

        List<String> deviceNames = new ArrayList<>();
        long devices;
        try (Stream<DeviceData> stream = client.streamDevices(networkId, snapshot.id(), () -> {})) {
            devices = writeBatches(networkId, snapshot.id(), stream.iterator(), DEVICE_QUERY, device -> {
                deviceNames.add(device.name());
                Map<String, Object> properties = values(
                    "displayName", device.displayName(), "type", device.type(), "vendor", device.vendor(),
                    "model", device.model(), "platform", device.platform(), "osVersion", device.osVersion(),
                    "locationId", device.locationId(), "managementIps", device.managementIps(), "tags", device.tags()
                );
                return values("id", device.name(), "properties", properties);
            });
        }
        if (devices == 0) throw new IllegalStateException("Forward inventory returned zero devices");
        deviceNames.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Function.identity()));

        long sourceLinks = 0;
        long skippedLinks = 0;
        List<Map<String, Object>> linkBatch = new ArrayList<>(batchSize);
        try (Stream<TopologyLinkData> stream = client.streamTopology(snapshot.id(), () -> {})) {
            for (Iterator<TopologyLinkData> links = stream.iterator(); links.hasNext();) {
                TopologyLinkData link = links.next();
                PortRef left = parsePort(link.sourcePort(), deviceNames);
                PortRef right = parsePort(link.targetPort(), deviceNames);
                if (left == null || right == null || left.equals(right)) {
                    skippedLinks++;
                    continue;
                }
                PortRef source = left.raw().compareTo(right.raw()) <= 0 ? left : right;
                PortRef target = source == left ? right : left;
                linkBatch.add(values(
                    "sourceId", source.raw(), "sourceDevice", source.device(), "sourceInterface", source.interfaceName(),
                    "targetId", target.raw(), "targetDevice", target.device(), "targetInterface", target.interfaceName()
                ));
                sourceLinks++;
                if (linkBatch.size() == batchSize) {
                    write(networkId, snapshot.id(), TOPOLOGY_QUERY, linkBatch);
                    linkBatch.clear();
                }
            }
        }
        if (!linkBatch.isEmpty()) write(networkId, snapshot.id(), TOPOLOGY_QUERY, linkBatch);
        long graphLinks = count(networkId, snapshot.id(),
            "MATCH (:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId})-[r:CONNECTED_TO]->(:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId}) RETURN count(r) AS value");
        completeJob(networkId, snapshot.id(), "inventory", devices + graphLinks, devices + graphLinks);

        LinkedHashSet<String> components = new LinkedHashSet<>();
        components.add("inventory");
        for (NqeProjection projection : NqeProjection.values()) {
            if (!config.licenseTier().enables(projection.feature())) continue;
            var execution = client.executeNqe(networkId, snapshot.id(), projection, () -> {});
            String query = projectionQuery(projection);
            long rows = writeBatches(
                networkId,
                snapshot.id(),
                client.nqeRows(networkId, snapshot.id(), execution.key(), () -> {}),
                query,
                Function.identity()
            );
            if (execution.rowsProduced() >= 0 && rows != execution.rowsProduced()) {
                throw new IllegalStateException("NQE row count mismatch for " + projection.projectionName());
            }
            recordProjection(networkId, snapshot.id(), projection, execution.key(), rows);
            completeJob(networkId, snapshot.id(), projection.projectionName(), rows, execution.rowsProduced());
            components.add(projection.projectionName());
        }

        if (config.licenseTier().enables(ForwardFeature.CHECKS)) {
            List<Map<String, Object>> checks = client.getChecks(snapshot.id()).stream()
                .filter(row -> row.get("id") != null).toList();
            writeLists(networkId, snapshot.id(), CHECKS_QUERY, checks);
            completeJob(networkId, snapshot.id(), "checks", checks.size(), checks.size());
            components.add("checks");
        }
        if (config.licenseTier().enables(ForwardFeature.VULNERABILITIES)) {
            var analysis = client.getVulnerabilities(networkId, snapshot.id());
            List<Map<String, Object>> vulnerabilities = analysis.vulnerabilities().stream()
                .filter(row -> row.get("id") != null)
                .toList();
            writeLists(networkId, snapshot.id(), VULNERABILITIES_QUERY, vulnerabilities);
            run("""
                MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
                SET snapshot.vulnerabilityIndexCreatedAt = $createdAt,
                    snapshot.vulnerabilityIndexUploadedAt = $uploadedAt
                """, values(
                    "networkId", networkId, "snapshotId", snapshot.id(),
                    "createdAt", analysis.indexCreatedAt(), "uploadedAt", analysis.indexUploadedAt()
                ));
            completeJob(networkId, snapshot.id(), "vulnerabilities", vulnerabilities.size(), vulnerabilities.size());
            components.add("vulnerabilities");
        }

        validate(networkId, snapshot.id(), List.copyOf(components));
        if (publish) publish(networkId, snapshot.id());
        return new ImportSummary(networkId, snapshot.id(), devices, graphLinks, components.size(), publish);
    }

    private void installSchema() {
        SCHEMA.forEach(statement -> run(statement, Map.of()));
    }

    private void stage(String networkId, ForwardApiClient.SnapshotData snapshot) {
        var existing = records(
            "MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId}) RETURN snapshot.publicationState AS state",
            values("networkId", networkId, "snapshotId", snapshot.id())
        );
        if (!existing.isEmpty() && "PUBLISHED".equals(existing.getFirst().get("state").asString(null))) {
            throw new IllegalStateException("Published snapshots are immutable");
        }
        run("""
            MERGE (network:ForwardNetwork {id: $networkId})
            MERGE (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            SET snapshot.createdAt = $createdAt, snapshot.processedAt = $processedAt,
                snapshot.importedAt = datetime(), snapshot.publicationState = coalesce(snapshot.publicationState, 'STAGED'),
                snapshot.validationState = 'PENDING'
            MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
            SET network.candidateSnapshotId = $snapshotId
            """,
            values(
                "networkId", networkId, "snapshotId", snapshot.id(),
                "createdAt", snapshot.createdAt(), "processedAt", snapshot.processedAt()
            )
        );
    }

    private String projectionQuery(NqeProjection projection) {
        return switch (projection) {
            case INTERFACES -> INTERFACES_QUERY;
            case INTERFACE_ADDRESSES -> ADDRESSES_QUERY;
            case NETWORK_INSTANCES -> INSTANCES_QUERY;
            case BGP_NEIGHBORS -> BGP_QUERY;
        };
    }

    private void recordProjection(String networkId, String snapshotId, NqeProjection projection, String key, long rows) {
        run("""
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            MERGE (run:ForwardProjectionRun {networkId: $networkId, snapshotId: $snapshotId,
              name: $name, version: $version, sha256: $sha256})
            SET run.executionKey = $executionKey, run.rowsImported = $rows, run.lastImportedAt = datetime()
            MERGE (snapshot)-[:HAS_PROJECTION]->(run)
            """, values(
                "networkId", networkId, "snapshotId", snapshotId, "name", projection.projectionName(),
                "version", projection.version(), "sha256", projection.sha256(), "executionKey", key, "rows", rows
            ));
    }

    private void completeJob(String networkId, String snapshotId, String component, long rows, long expected) {
        if (expected >= 0 && rows != expected) throw new IllegalStateException("Row count mismatch for " + component);
        run("""
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            MERGE (job:ForwardImportJob {networkId: $networkId, snapshotId: $snapshotId, component: $component})
            ON CREATE SET job.createdAt = datetime(), job.attempt = 0
            SET job.state = 'VALIDATED', job.attempt = job.attempt + 1, job.submittedAt = datetime(),
                job.startedAt = datetime(), job.completedAt = datetime(), job.rowsImported = $rows,
                job.expectedRows = $expected, job.error = null
            MERGE (snapshot)-[:HAS_IMPORT_JOB]->(job)
            """, values(
                "networkId", networkId, "snapshotId", snapshotId, "component", component,
                "rows", rows, "expected", expected < 0 ? rows : expected
            ));
    }

    private void validate(String networkId, String snapshotId, List<String> components) {
        Record result = records("""
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            CALL (snapshot) { OPTIONAL MATCH (snapshot)-[:CONTAINS]->(d:ForwardDevice) RETURN count(DISTINCT d) AS devices }
            CALL () { MATCH (i:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId})
              WHERE NOT EXISTS { MATCH (:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId})-[:HAS_INTERFACE]->(i) }
              RETURN count(i) AS orphanInterfaces }
            CALL () { MATCH (a:ForwardIpAddress {networkId: $networkId, snapshotId: $snapshotId})
              WHERE NOT EXISTS { MATCH ()-[:HAS_IP_ADDRESS]->(a) } RETURN count(a) AS orphanAddresses }
            CALL () { MATCH (n:ForwardNetworkInstance {networkId: $networkId, snapshotId: $snapshotId})
              WHERE NOT EXISTS { MATCH (:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId})-[:HAS_NETWORK_INSTANCE]->(n) }
              RETURN count(n) AS orphanInstances }
            CALL () { MATCH (b:ForwardBgpNeighbor {networkId: $networkId, snapshotId: $snapshotId})
              WHERE NOT EXISTS { MATCH ()-[:HAS_BGP_NEIGHBOR]->(b) } RETURN count(b) AS orphanBgp }
            RETURN devices, orphanInterfaces, orphanAddresses, orphanInstances, orphanBgp
            """, values("networkId", networkId, "snapshotId", snapshotId)).getFirst();
        List<String> errors = new ArrayList<>();
        if (result.get("devices").asLong() == 0) errors.add("snapshot contains no devices");
        for (String field : List.of("orphanInterfaces", "orphanAddresses", "orphanInstances", "orphanBgp")) {
            if (result.get(field).asLong() != 0) errors.add(field + "=" + result.get(field).asLong());
        }
        long validatedJobs = count(networkId, snapshotId,
            "MATCH (j:ForwardImportJob {networkId: $networkId, snapshotId: $snapshotId, state: 'VALIDATED'}) WHERE j.component IN $components RETURN count(j) AS value",
            values("components", components));
        if (validatedJobs != components.size()) errors.add("required component jobs are missing");
        if (!errors.isEmpty()) throw new IllegalStateException("Snapshot validation failed: " + String.join(", ", errors));
        run("""
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            SET snapshot.validationState = 'VALIDATED', snapshot.validatedComponents = $components,
                snapshot.validationErrors = [], snapshot.validatedAt = datetime()
            """, values("networkId", networkId, "snapshotId", snapshotId, "components", components));
    }

    private void publish(String networkId, String snapshotId) {
        run("""
            MATCH (network:ForwardNetwork {id: $networkId})
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId, validationState: 'VALIDATED'})
            OPTIONAL MATCH (previous:ForwardSnapshot {networkId: $networkId, id: network.currentSnapshotId})
            WITH network, snapshot, previous, network.currentSnapshotId AS previousSnapshotId
            FOREACH (_ IN CASE WHEN previous IS NOT NULL AND previous.id <> snapshot.id THEN [1] ELSE [] END |
              SET previous.publicationState = 'SUPERSEDED', previous.supersededAt = datetime())
            SET network.previousSnapshotId = CASE WHEN previousSnapshotId IS NULL OR previousSnapshotId = $snapshotId
                  THEN network.previousSnapshotId ELSE previousSnapshotId END,
                network.currentSnapshotId = $snapshotId, network.publishedAt = datetime(),
                snapshot.publicationState = 'PUBLISHED', snapshot.publishedAt = datetime()
            REMOVE network.candidateSnapshotId
            """, values("networkId", networkId, "snapshotId", snapshotId));
    }

    private <T> long writeBatches(
        String networkId,
        String snapshotId,
        Iterator<T> source,
        String query,
        Function<T, Map<String, Object>> mapper
    ) {
        long count = 0;
        List<Map<String, Object>> batch = new ArrayList<>(batchSize);
        while (source.hasNext()) {
            batch.add(mapper.apply(source.next()));
            count++;
            if (batch.size() == batchSize) {
                write(networkId, snapshotId, query, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) write(networkId, snapshotId, query, batch);
        return count;
    }

    private void writeLists(String networkId, String snapshotId, String query, List<Map<String, Object>> rows) {
        for (int start = 0; start < rows.size(); start += batchSize) {
            write(networkId, snapshotId, query, rows.subList(start, Math.min(rows.size(), start + batchSize)));
        }
    }

    private void write(String networkId, String snapshotId, String query, List<Map<String, Object>> rows) {
        run(query, values("networkId", networkId, "snapshotId", snapshotId, "rows", List.copyOf(rows)));
    }

    private long count(String networkId, String snapshotId, String query) {
        return count(networkId, snapshotId, query, Map.of());
    }

    private long count(String networkId, String snapshotId, String query, Map<String, Object> extra) {
        Map<String, Object> parameters = new HashMap<>(extra);
        parameters.put("networkId", networkId);
        parameters.put("snapshotId", snapshotId);
        return records(query, parameters).getFirst().get("value").asLong();
    }

    private void run(String query, Map<String, Object> parameters) {
        try (var session = driver.session(sessionConfig)) {
            session.executeWriteWithoutResult(transaction -> transaction.run(query, parameters).consume());
        }
    }

    private List<Record> records(String query, Map<String, Object> parameters) {
        try (var session = driver.session(sessionConfig)) {
            return session.run(query, parameters).list();
        }
    }

    private static PortRef parsePort(String raw, List<String> deviceNames) {
        for (String device : deviceNames) {
            String prefix = device + ' ';
            if (raw.startsWith(prefix) && raw.length() > prefix.length()) {
                return new PortRef(raw, device, raw.substring(prefix.length()));
            }
        }
        return null;
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    record ImportSummary(String networkId, String snapshotId, long devices, long links, long components, boolean published) {}

    private record PortRef(String raw, String device, String interfaceName) {}
}
