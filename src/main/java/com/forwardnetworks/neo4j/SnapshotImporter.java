package com.forwardnetworks.neo4j;

import com.forwardnetworks.neo4j.ForwardApiClient.DeviceData;
import com.forwardnetworks.neo4j.ForwardApiClient.SnapshotData;
import com.forwardnetworks.neo4j.ForwardApiClient.TopologyLinkData;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.graphdb.Transaction;

final class SnapshotImporter {

    private final Transaction transaction;

    SnapshotImporter(Transaction transaction) {
        this.transaction = transaction;
    }

    ImportStats importSnapshot(
        String networkId,
        SnapshotData snapshot,
        List<DeviceData> devices,
        List<TopologyLinkData> topology
    ) {
        assertSnapshotMutable(networkId, snapshot.id());
        mergeNetworkAndSnapshot(networkId, snapshot);
        mergeDevices(networkId, snapshot.id(), devices);

        List<String> deviceNames = devices.stream().map(DeviceData::name).sorted(Comparator.comparingInt(String::length).reversed()).toList();
        Set<LinkKey> uniqueLinks = new HashSet<>();
        int skippedLinks = 0;
        for (TopologyLinkData link : topology) {
            PortRef source = parsePort(link.sourcePort(), deviceNames);
            PortRef target = parsePort(link.targetPort(), deviceNames);
            if (source == null || target == null || source.equals(target)) {
                skippedLinks++;
                continue;
            }
            LinkKey key = LinkKey.canonical(source, target);
            if (uniqueLinks.add(key)) {
                // Imported in one set-oriented statement after validation and de-duplication.
            }
        }
        mergeLinks(networkId, snapshot.id(), uniqueLinks);
        var stats = new ImportStats(devices.size(), uniqueLinks.size(), skippedLinks);
        mergeInventoryJob(networkId, snapshot.id(), stats);
        return stats;
    }

    private void assertSnapshotMutable(String networkId, String snapshotId) {
        try (var rows = transaction.execute(
            """
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            RETURN snapshot.publicationState AS publicationState
            """,
            Map.of("networkId", networkId, "snapshotId", snapshotId)
        )) {
            if (rows.hasNext() && "PUBLISHED".equals(rows.next().get("publicationState"))) {
                throw new IllegalStateException(
                    "Snapshot " + snapshotId + " is already published and is immutable; import a new snapshot instead"
                );
            }
        }
    }

    private void mergeNetworkAndSnapshot(String networkId, SnapshotData snapshot) {
        transaction.execute(
            """
            MERGE (network:ForwardNetwork {id: $networkId})
            MERGE (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            SET snapshot.createdAt = $createdAt,
                snapshot.processedAt = $processedAt,
                snapshot.importedAt = datetime(),
                snapshot.publicationState = coalesce(snapshot.publicationState, 'STAGED'),
                snapshot.validationState = 'PENDING'
            MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
            SET network.candidateSnapshotId = $snapshotId
            """,
            properties(
                "networkId",
                networkId,
                "snapshotId",
                snapshot.id(),
                "createdAt",
                snapshot.createdAt(),
                "processedAt",
                snapshot.processedAt()
            )
        );
    }

    private void mergeInventoryJob(String networkId, String snapshotId, ImportStats stats) {
        transaction.execute(
            """
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            MERGE (job:ForwardImportJob {
              networkId: $networkId,
              snapshotId: $snapshotId,
              component: 'inventory'
            })
            ON CREATE SET job.createdAt = datetime(), job.attempt = 0
            SET job.state = 'VALIDATED',
                job.description = 'Device inventory and physical topology',
                job.attempt = coalesce(job.attempt, 0) + 1,
                job.submittedAt = datetime(),
                job.startedAt = datetime(),
                job.completedAt = datetime(),
                job.failedAt = null,
                job.error = null,
                job.rowsImported = $rowsImported,
                job.expectedRows = $rowsImported,
                job.deviceCount = $devices,
                job.linkCount = $links,
                job.skippedLinkCount = $skippedLinks
            MERGE (snapshot)-[:HAS_IMPORT_JOB]->(job)
            """,
            Map.of(
                "networkId", networkId,
                "snapshotId", snapshotId,
                "rowsImported", stats.devices() + stats.links(),
                "devices", stats.devices(),
                "links", stats.links(),
                "skippedLinks", stats.skippedLinks()
            )
        );
    }

    private void mergeDevices(String networkId, String snapshotId, List<DeviceData> devices) {
        List<Map<String, Object>> rows = devices.stream().map(device -> properties(
            "name", device.name(),
            "displayName", device.displayName(),
            "type", device.type(),
            "vendor", device.vendor(),
            "model", device.model(),
            "platform", device.platform(),
            "osVersion", device.osVersion(),
            "locationId", device.locationId(),
            "managementIps", device.managementIps(),
            "tags", device.tags()
        )).toList();
        transaction.execute(
            """
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            UNWIND $devices AS item
            MERGE (device:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: item.name})
            SET device.displayName = item.displayName,
                device.type = item.type,
                device.vendor = item.vendor,
                device.model = item.model,
                device.platform = item.platform,
                device.osVersion = item.osVersion,
                device.locationId = item.locationId,
                device.managementIps = item.managementIps,
                device.tags = item.tags
            MERGE (snapshot)-[:CONTAINS]->(device)
            """,
            Map.of("networkId", networkId, "snapshotId", snapshotId, "devices", rows)
        );
    }

    private void mergeLinks(String networkId, String snapshotId, Set<LinkKey> links) {
        List<Map<String, Object>> rows = links.stream().map(link -> Map.<String, Object>of(
            "sourceId", link.source().raw(),
            "sourceDevice", link.source().deviceName(),
            "sourceInterface", link.source().interfaceName(),
            "targetId", link.target().raw(),
            "targetDevice", link.target().deviceName(),
            "targetInterface", link.target().interfaceName()
        )).toList();
        transaction.execute(
            """
            UNWIND $links AS item
            MATCH (sourceDevice:ForwardDevice {
              networkId: $networkId, snapshotId: $snapshotId, id: item.sourceDevice
            })
            MATCH (targetDevice:ForwardDevice {
              networkId: $networkId, snapshotId: $snapshotId, id: item.targetDevice
            })
            MERGE (source:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: item.sourceId})
            SET source.name = item.sourceInterface, source.deviceName = item.sourceDevice
            MERGE (target:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: item.targetId})
            SET target.name = item.targetInterface, target.deviceName = item.targetDevice
            MERGE (sourceDevice)-[:HAS_INTERFACE]->(source)
            MERGE (targetDevice)-[:HAS_INTERFACE]->(target)
            MERGE (source)-[:CONNECTED_TO]->(target)
            """,
            Map.of("networkId", networkId, "snapshotId", snapshotId, "links", rows)
        );
    }

    private static PortRef parsePort(String raw, List<String> deviceNames) {
        for (String deviceName : deviceNames) {
            String prefix = deviceName + ' ';
            if (raw.startsWith(prefix) && raw.length() > prefix.length()) {
                return new PortRef(raw, deviceName, raw.substring(prefix.length()));
            }
        }
        return null;
    }

    private static Map<String, Object> properties(Object... pairs) {
        Map<String, Object> result = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    record ImportStats(long devices, long links, long skippedLinks) {}

    private record PortRef(String raw, String deviceName, String interfaceName) {}

    private record LinkKey(PortRef source, PortRef target) {
        static LinkKey canonical(PortRef left, PortRef right) {
            return left.raw().compareTo(right.raw()) <= 0 ? new LinkKey(left, right) : new LinkKey(right, left);
        }
    }
}
