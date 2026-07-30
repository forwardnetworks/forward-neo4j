package com.forwardnetworks.neo4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.neo4j.graphdb.Transaction;

final class MetricsCollector {

    private final Transaction transaction;

    MetricsCollector(Transaction transaction) {
        this.transaction = transaction;
    }

    List<String> prometheus(String networkId) {
        String filter = networkId == null ? "" : networkId;
        List<String> lines = new ArrayList<>();
        metadata(lines, "forward_neo4j_network_info", "Forward networks managed by the integration", "gauge");
        metadata(lines, "forward_neo4j_candidate_pending", "Whether a network has an unpublished candidate snapshot", "gauge");
        metadata(lines, "forward_neo4j_devices_current", "Devices in the current published snapshot", "gauge");
        metadata(lines, "forward_neo4j_current_snapshot_age_seconds", "Age of the current published snapshot", "gauge");
        metadata(lines, "forward_neo4j_snapshots_total", "Managed snapshots by publication state", "gauge");
        metadata(lines, "forward_neo4j_import_jobs_total", "Snapshot import jobs by state", "gauge");

        try (var rows = transaction.execute(
            """
            MATCH (network:ForwardNetwork)
            WHERE $networkId = '' OR network.id = $networkId
            OPTIONAL MATCH (current:ForwardSnapshot {
              networkId: network.id, id: network.currentSnapshotId
            })
            OPTIONAL MATCH (current)-[:CONTAINS]->(device:ForwardDevice)
            RETURN network.id AS networkId,
                   network.currentSnapshotId AS currentSnapshotId,
                   network.candidateSnapshotId AS candidateSnapshotId,
                   current.publishedAt.epochMillis AS publishedEpochMillis,
                   count(DISTINCT device) AS devices
            ORDER BY networkId
            """,
            Map.of("networkId", filter)
        )) {
            rows.forEachRemaining(row -> {
                String id = (String) row.get("networkId");
                String current = (String) row.get("currentSnapshotId");
                String candidate = (String) row.get("candidateSnapshotId");
                String labels = "network_id=\"" + escapeLabel(id) + "\"";
                lines.add("forward_neo4j_network_info{" + labels + "} 1");
                lines.add(
                    "forward_neo4j_candidate_pending{" + labels + "} " +
                    (candidate != null && !candidate.equals(current) ? "1" : "0")
                );
                lines.add(
                    "forward_neo4j_devices_current{" + labels + "} " +
                    ((Number) row.get("devices")).longValue()
                );
                Number publishedEpochMillis = (Number) row.get("publishedEpochMillis");
                if (publishedEpochMillis != null) {
                    long ageSeconds = Math.max(
                        0,
                        (Instant.now().toEpochMilli() - publishedEpochMillis.longValue()) / 1_000
                    );
                    lines.add("forward_neo4j_current_snapshot_age_seconds{" + labels + "} " + ageSeconds);
                }
            });
        }

        appendGroupedMetric(
            lines,
            "forward_neo4j_snapshots_total",
            filter,
            """
            MATCH (snapshot:ForwardSnapshot)
            WHERE $networkId = '' OR snapshot.networkId = $networkId
            RETURN snapshot.networkId AS networkId,
                   coalesce(snapshot.publicationState, 'UNKNOWN') AS state,
                   count(*) AS value
            ORDER BY networkId, state
            """
        );
        appendGroupedMetric(
            lines,
            "forward_neo4j_import_jobs_total",
            filter,
            """
            MATCH (job:ForwardImportJob)
            WHERE $networkId = '' OR job.networkId = $networkId
            RETURN job.networkId AS networkId,
                   coalesce(job.state, 'UNKNOWN') AS state,
                   count(*) AS value
            ORDER BY networkId, state
            """
        );
        return List.copyOf(lines);
    }

    private void appendGroupedMetric(List<String> lines, String metric, String networkId, String query) {
        try (var rows = transaction.execute(query, Map.of("networkId", networkId))) {
            rows.forEachRemaining(row -> lines.add(
                metric +
                "{network_id=\"" + escapeLabel((String) row.get("networkId")) +
                "\",state=\"" + escapeLabel((String) row.get("state")) +
                "\"} " + ((Number) row.get("value")).longValue()
            ));
        }
    }

    private static void metadata(List<String> lines, String metric, String help, String type) {
        lines.add("# HELP " + metric + " " + help);
        lines.add("# TYPE " + metric + " " + type);
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"");
    }
}
