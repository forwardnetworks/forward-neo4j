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
        mergeNetworkAndSnapshot(networkId, snapshot);
        devices.forEach(device -> mergeDevice(networkId, snapshot.id(), device));

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
                mergeLink(networkId, snapshot.id(), key);
            }
        }
        return new ImportStats(devices.size(), uniqueLinks.size(), skippedLinks);
    }

    private void mergeNetworkAndSnapshot(String networkId, SnapshotData snapshot) {
        transaction.execute(
            """
            MERGE (network:ForwardNetwork {id: $networkId})
            MERGE (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            SET snapshot.createdAt = $createdAt,
                snapshot.processedAt = $processedAt,
                snapshot.importedAt = datetime()
            MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
            SET network.currentSnapshotId = $snapshotId
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

    private void mergeDevice(String networkId, String snapshotId, DeviceData device) {
        transaction.execute(
            """
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            MERGE (device:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: $name})
            SET device.displayName = $displayName,
                device.type = $type,
                device.vendor = $vendor,
                device.model = $model,
                device.platform = $platform,
                device.osVersion = $osVersion,
                device.locationId = $locationId,
                device.managementIps = $managementIps,
                device.tags = $tags
            MERGE (snapshot)-[:CONTAINS]->(device)
            """,
            properties(
                "networkId", networkId,
                "snapshotId", snapshotId,
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
            )
        );
    }

    private void mergeLink(String networkId, String snapshotId, LinkKey link) {
        mergeInterface(networkId, snapshotId, link.source());
        mergeInterface(networkId, snapshotId, link.target());
        transaction.execute(
            """
            MATCH (source:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: $sourceId})
            MATCH (target:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: $targetId})
            MERGE (source)-[:CONNECTED_TO]->(target)
            """,
            Map.of(
                "networkId", networkId,
                "snapshotId", snapshotId,
                "sourceId", link.source().raw(),
                "targetId", link.target().raw()
            )
        );
    }

    private void mergeInterface(String networkId, String snapshotId, PortRef port) {
        transaction.execute(
            """
            MATCH (device:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId, id: $deviceName})
            MERGE (interface:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId, id: $id})
            SET interface.name = $interfaceName,
                interface.deviceName = $deviceName
            MERGE (device)-[:HAS_INTERFACE]->(interface)
            """,
            Map.of(
                "networkId", networkId,
                "snapshotId", snapshotId,
                "deviceName", port.deviceName(),
                "interfaceName", port.interfaceName(),
                "id", port.raw()
            )
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
