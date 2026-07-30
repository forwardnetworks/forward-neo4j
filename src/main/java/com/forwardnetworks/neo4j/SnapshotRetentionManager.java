package com.forwardnetworks.neo4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.graphdb.Transaction;

final class SnapshotRetentionManager {

    private static final int MAX_PRUNE_BATCH = 100;
    private static final List<String> MANAGED_SNAPSHOT_LABELS = List.of(
        "ForwardSnapshot",
        "ForwardDevice",
        "ForwardInterface",
        "ForwardSubinterface",
        "ForwardIpAddress",
        "ForwardNetworkInstance",
        "ForwardBgpNeighbor",
        "ForwardProjectionRun",
        "ForwardImportJob",
        "ForwardCheckObservation",
        "ForwardCheckDiagnosisDetail",
        "ForwardVulnerability",
        "ForwardVulnerabilityOs",
        "ForwardPathObservation",
        "ForwardPathResult",
        "ForwardPathHop"
    );

    private final Transaction transaction;

    SnapshotRetentionManager(Transaction transaction) {
        this.transaction = transaction;
    }

    List<RetentionCandidate> preview(String networkId, long keepPublished, long minimumAgeDays) {
        if (keepPublished < 2 || keepPublished > 1_000) {
            throw new IllegalArgumentException("keepPublished must be between 2 and 1000");
        }
        if (minimumAgeDays < 0 || minimumAgeDays > 36_500) {
            throw new IllegalArgumentException("minimumAgeDays must be between 0 and 36500");
        }
        PointerState pointers = pointers(networkId);
        List<SnapshotRow> snapshots = snapshots(networkId);
        Set<String> retainedPublished = snapshots.stream()
            .filter(row -> "PUBLISHED".equals(row.publicationState()) || "SUPERSEDED".equals(row.publicationState()))
            .sorted(java.util.Comparator.comparingLong(SnapshotRow::sortEpochMillis).reversed())
            .limit(keepPublished)
            .map(SnapshotRow::snapshotId)
            .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        long cutoffEpochMillis = System.currentTimeMillis() - Math.multiplyExact(minimumAgeDays, 86_400_000L);

        List<RetentionCandidate> result = new ArrayList<>(snapshots.size());
        for (SnapshotRow snapshot : snapshots) {
            String reason;
            boolean protectedSnapshot = true;
            if (snapshot.snapshotId().equals(pointers.currentSnapshotId())) {
                reason = "CURRENT";
            } else if (snapshot.snapshotId().equals(pointers.previousSnapshotId())) {
                reason = "PREVIOUS";
            } else if (snapshot.snapshotId().equals(pointers.candidateSnapshotId())) {
                reason = "CANDIDATE";
            } else if (retainedPublished.contains(snapshot.snapshotId())) {
                reason = "RETAINED_PUBLISHED";
            } else if (snapshot.sortEpochMillis() > cutoffEpochMillis) {
                reason = "TOO_RECENT";
            } else {
                protectedSnapshot = false;
                reason = "ELIGIBLE";
            }
            result.add(new RetentionCandidate(
                networkId,
                snapshot.snapshotId(),
                snapshot.publicationState(),
                snapshot.importedAt(),
                snapshot.publishedAt(),
                protectedSnapshot,
                !protectedSnapshot,
                reason
            ));
        }
        return List.copyOf(result);
    }

    PruneResult prune(String networkId, List<String> suppliedSnapshotIds) {
        if (suppliedSnapshotIds == null || suppliedSnapshotIds.isEmpty()) {
            throw new IllegalArgumentException("snapshotIds must contain at least one explicit snapshot ID");
        }
        if (suppliedSnapshotIds.size() > MAX_PRUNE_BATCH) {
            throw new IllegalArgumentException("snapshotIds may contain at most " + MAX_PRUNE_BATCH + " entries");
        }
        LinkedHashSet<String> snapshotIds = new LinkedHashSet<>();
        for (String snapshotId : suppliedSnapshotIds) {
            if (snapshotId == null || snapshotId.isBlank()) {
                throw new IllegalArgumentException("snapshotIds must not contain blank values");
            }
            snapshotIds.add(snapshotId);
        }

        PointerState pointers = pointers(networkId);
        Set<String> protectedIds = new HashSet<>();
        if (pointers.currentSnapshotId() != null) protectedIds.add(pointers.currentSnapshotId());
        if (pointers.previousSnapshotId() != null) protectedIds.add(pointers.previousSnapshotId());
        if (pointers.candidateSnapshotId() != null) protectedIds.add(pointers.candidateSnapshotId());
        Set<String> requestedProtected = new LinkedHashSet<>(snapshotIds);
        requestedProtected.retainAll(protectedIds);
        if (!requestedProtected.isEmpty()) {
            throw new IllegalStateException(
                "Refusing to prune current, previous, or candidate snapshots: " + String.join(", ", requestedProtected)
            );
        }

        Set<String> existing = new HashSet<>();
        try (var rows = transaction.execute(
            """
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId})
            WHERE snapshot.id IN $snapshotIds
            RETURN snapshot.id AS snapshotId
            """,
            Map.of("networkId", networkId, "snapshotIds", List.copyOf(snapshotIds))
        )) {
            rows.forEachRemaining(row -> existing.add((String) row.get("snapshotId")));
        }
        Set<String> missing = new LinkedHashSet<>(snapshotIds);
        missing.removeAll(existing);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Snapshots do not exist for network " + networkId + ": " + String.join(", ", missing));
        }

        long deletedNodes;
        try (var rows = transaction.execute(
            """
            MATCH (node)
            WHERE node.networkId = $networkId
              AND (node.snapshotId IN $snapshotIds OR (node:ForwardSnapshot AND node.id IN $snapshotIds))
              AND any(label IN labels(node) WHERE label IN $managedLabels)
            WITH collect(node) AS nodes
            FOREACH (node IN nodes | DETACH DELETE node)
            RETURN size(nodes) AS deletedNodes
            """,
            Map.of(
                "networkId", networkId,
                "snapshotIds", List.copyOf(snapshotIds),
                "managedLabels", MANAGED_SNAPSHOT_LABELS
            )
        )) {
            deletedNodes = ((Number) rows.next().get("deletedNodes")).longValue();
        }
        return new PruneResult(networkId, List.copyOf(snapshotIds), deletedNodes);
    }

    private PointerState pointers(String networkId) {
        try (var rows = transaction.execute(
            """
            MATCH (network:ForwardNetwork {id: $networkId})
            RETURN network.currentSnapshotId AS currentSnapshotId,
                   network.previousSnapshotId AS previousSnapshotId,
                   network.candidateSnapshotId AS candidateSnapshotId
            """,
            Map.of("networkId", networkId)
        )) {
            if (!rows.hasNext()) {
                throw new IllegalStateException("Forward network does not exist: " + networkId);
            }
            var row = rows.next();
            return new PointerState(
                (String) row.get("currentSnapshotId"),
                (String) row.get("previousSnapshotId"),
                (String) row.get("candidateSnapshotId")
            );
        }
    }

    private List<SnapshotRow> snapshots(String networkId) {
        List<SnapshotRow> result = new ArrayList<>();
        try (var rows = transaction.execute(
            """
            MATCH (:ForwardNetwork {id: $networkId})-[:HAS_SNAPSHOT]->(snapshot:ForwardSnapshot)
            RETURN snapshot.id AS snapshotId,
                   snapshot.publicationState AS publicationState,
                   toString(snapshot.importedAt) AS importedAt,
                   toString(snapshot.publishedAt) AS publishedAt,
                   coalesce(snapshot.publishedAt, snapshot.importedAt, datetime({epochMillis: 0})).epochMillis
                     AS sortEpochMillis
            ORDER BY sortEpochMillis DESC, snapshotId
            """,
            Map.of("networkId", networkId)
        )) {
            rows.forEachRemaining(row -> result.add(new SnapshotRow(
                (String) row.get("snapshotId"),
                (String) row.get("publicationState"),
                (String) row.get("importedAt"),
                (String) row.get("publishedAt"),
                ((Number) row.get("sortEpochMillis")).longValue()
            )));
        }
        return List.copyOf(result);
    }

    record RetentionCandidate(
        String networkId,
        String snapshotId,
        String publicationState,
        String importedAt,
        String publishedAt,
        boolean protectedSnapshot,
        boolean eligible,
        String reason
    ) {}

    record PruneResult(String networkId, List<String> snapshotIds, long deletedNodes) {}

    private record PointerState(String currentSnapshotId, String previousSnapshotId, String candidateSnapshotId) {}

    private record SnapshotRow(
        String snapshotId,
        String publicationState,
        String importedAt,
        String publishedAt,
        long sortEpochMillis
    ) {}
}
