package com.forwardnetworks.neo4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.neo4j.graphdb.Transaction;

final class ImportJobManager {

    private final Transaction transaction;

    ImportJobManager(Transaction transaction) {
        this.transaction = transaction;
    }

    JobData submit(String networkId, String snapshotId, ImportComponent component) {
        requireMutableSnapshot(networkId, snapshotId);
        Map<String, Object> row = one(
            """
            MATCH (network:ForwardNetwork {id: $networkId})
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            MERGE (job:ForwardImportJob {
              networkId: $networkId,
              snapshotId: $snapshotId,
              component: $component
            })
            ON CREATE SET job.createdAt = datetime(), job.attempt = 0
            SET job.state = 'SUBMITTED',
                job.description = $description,
                job.submittedAt = datetime(),
                job.startedAt = null,
                job.completedAt = null,
                job.failedAt = null,
                job.error = null,
                job.rowsImported = null,
                job.expectedRows = null,
                snapshot.validationState = 'PENDING',
                snapshot.publicationState = coalesce(snapshot.publicationState, 'STAGED'),
                network.candidateSnapshotId = CASE
                  WHEN coalesce(snapshot.kind, '') = 'PREDICTED' THEN network.candidateSnapshotId
                  ELSE $snapshotId
                END
            MERGE (snapshot)-[:HAS_IMPORT_JOB]->(job)
            RETURN job.component AS component, job.state AS state, job.attempt AS attempt,
                   toString(job.submittedAt) AS submittedAt
            """,
            Map.of(
                "networkId", networkId,
                "snapshotId", snapshotId,
                "component", component.id(),
                "description", component.description()
            )
        );
        return new JobData(
            networkId,
            snapshotId,
            text(row, "component"),
            text(row, "state"),
            number(row, "attempt"),
            null,
            null,
            text(row, "submittedAt"),
            null,
            null,
            null
        );
    }

    JobData begin(String networkId, String snapshotId, ImportComponent component) {
        SnapshotState snapshot = requireMutableSnapshot(networkId, snapshotId);
        Map<String, Object> row = one(
            """
            MATCH (network:ForwardNetwork {id: $networkId})
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            MERGE (job:ForwardImportJob {
              networkId: $networkId,
              snapshotId: $snapshotId,
              component: $component
            })
            ON CREATE SET job.createdAt = datetime(), job.attempt = 0
            SET job.state = 'IMPORTING',
                job.description = $description,
                job.attempt = coalesce(job.attempt, 0) + 1,
                job.submittedAt = coalesce(job.submittedAt, datetime()),
                job.startedAt = datetime(),
                job.completedAt = null,
                job.failedAt = null,
                job.error = null,
                job.rowsImported = null,
                job.expectedRows = null,
                snapshot.validationState = 'PENDING',
                snapshot.publicationState = coalesce(snapshot.publicationState, 'STAGED'),
                network.candidateSnapshotId = CASE
                  WHEN coalesce(snapshot.kind, '') = 'PREDICTED' THEN network.candidateSnapshotId
                  ELSE $snapshotId
                END
            MERGE (snapshot)-[:HAS_IMPORT_JOB]->(job)
            RETURN job.component AS component, job.state AS state, job.attempt AS attempt,
                   toString(job.submittedAt) AS submittedAt, toString(job.startedAt) AS startedAt
            """,
            Map.of(
                "networkId", networkId,
                "snapshotId", snapshotId,
                "component", component.id(),
                "description", component.description()
            )
        );
        return new JobData(
            networkId,
            snapshotId,
            text(row, "component"),
            text(row, "state"),
            number(row, "attempt"),
            null,
            null,
            text(row, "submittedAt"),
            text(row, "startedAt"),
            null,
            null
        );
    }

    JobData complete(
        String networkId,
        String snapshotId,
        ImportComponent component,
        long rowsImported,
        long expectedRows
    ) {
        if (rowsImported < 0 || expectedRows < -1) {
            throw new IllegalArgumentException("rowsImported must be non-negative and expectedRows must be -1 or non-negative");
        }
        requireMutableSnapshot(networkId, snapshotId);
        Map<String, Object> current = requireJob(networkId, snapshotId, component);
        String currentState = text(current, "state");
        if (!"IMPORTING".equals(currentState) && !"VALIDATED".equals(currentState)) {
            throw new IllegalStateException(
                "Import component '" + component.id() + "' cannot complete from state " + currentState
            );
        }

        boolean countsMatch = expectedRows < 0 || rowsImported == expectedRows;
        String state = countsMatch ? "VALIDATED" : "FAILED";
        String error = countsMatch
            ? null
            : "row count mismatch: expected " + expectedRows + " but imported " + rowsImported;
        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("networkId", networkId);
        parameters.put("snapshotId", snapshotId);
        parameters.put("component", component.id());
        parameters.put("rowsImported", rowsImported);
        parameters.put("expectedRows", expectedRows < 0 ? null : expectedRows);
        parameters.put("state", state);
        parameters.put("error", error);
        Map<String, Object> row = one(
            """
            MATCH (job:ForwardImportJob {
              networkId: $networkId,
              snapshotId: $snapshotId,
              component: $component
            })
            SET job.state = $state,
                job.rowsImported = $rowsImported,
                job.expectedRows = $expectedRows,
                job.completedAt = CASE WHEN $state = 'VALIDATED' THEN datetime() ELSE null END,
                job.failedAt = CASE WHEN $state = 'FAILED' THEN datetime() ELSE null END,
                job.error = $error
            RETURN job.component AS component, job.state AS state, job.attempt AS attempt,
                   job.rowsImported AS rowsImported, job.expectedRows AS expectedRows,
                   toString(job.submittedAt) AS submittedAt, toString(job.startedAt) AS startedAt,
                   toString(job.completedAt) AS completedAt,
                   job.error AS error
            """,
            parameters
        );
        return jobData(networkId, snapshotId, row);
    }

    JobData fail(String networkId, String snapshotId, ImportComponent component, String suppliedError) {
        requireMutableSnapshot(networkId, snapshotId);
        requireJob(networkId, snapshotId, component);
        String error = sanitizeError(suppliedError);
        Map<String, Object> row = one(
            """
            MATCH (job:ForwardImportJob {
              networkId: $networkId,
              snapshotId: $snapshotId,
              component: $component
            })
            SET job.state = 'FAILED', job.failedAt = datetime(), job.completedAt = null, job.error = $error
            RETURN job.component AS component, job.state AS state, job.attempt AS attempt,
                   job.rowsImported AS rowsImported, job.expectedRows AS expectedRows,
                   toString(job.submittedAt) AS submittedAt, toString(job.startedAt) AS startedAt,
                   toString(job.completedAt) AS completedAt,
                   job.error AS error
            """,
            Map.of(
                "networkId", networkId,
                "snapshotId", snapshotId,
                "component", component.id(),
                "error", error
            )
        );
        return jobData(networkId, snapshotId, row);
    }

    List<JobData> jobs(String networkId, String snapshotId) {
        List<JobData> jobs = new ArrayList<>();
        try (var rows = transaction.execute(
            """
            MATCH (:ForwardSnapshot {networkId: $networkId, id: $snapshotId})-[:HAS_IMPORT_JOB]->(job:ForwardImportJob)
            RETURN job.component AS component, job.state AS state, job.attempt AS attempt,
                   job.rowsImported AS rowsImported, job.expectedRows AS expectedRows,
                   toString(job.submittedAt) AS submittedAt, toString(job.startedAt) AS startedAt,
                   toString(job.completedAt) AS completedAt,
                   job.error AS error
            ORDER BY job.component
            """,
            Map.of("networkId", networkId, "snapshotId", snapshotId)
        )) {
            rows.forEachRemaining(row -> jobs.add(jobData(networkId, snapshotId, row)));
        }
        return List.copyOf(jobs);
    }

    ValidationData validate(
        String networkId,
        String snapshotId,
        List<ImportComponent> requestedComponents
    ) {
        requireMutableSnapshot(networkId, snapshotId);
        LinkedHashSet<String> required = new LinkedHashSet<>();
        required.add(ImportComponent.INVENTORY.id());
        requestedComponents.forEach(component -> required.add(component.id()));

        Map<String, JobData> jobsByComponent = jobs(networkId, snapshotId)
            .stream()
            .collect(java.util.stream.Collectors.toMap(JobData::component, job -> job));
        List<String> missing = required.stream()
            .filter(component -> {
                JobData job = jobsByComponent.get(component);
                return job == null || !"VALIDATED".equals(job.state());
            })
            .toList();

        Map<String, Object> counts = one(
            """
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            CALL (snapshot) {
              OPTIONAL MATCH (snapshot)-[:CONTAINS]->(device:ForwardDevice)
              RETURN count(DISTINCT device) AS devices
            }
            CALL () {
              MATCH (interface:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId})
              WHERE NOT EXISTS {
                MATCH (:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId})-[:HAS_INTERFACE]->(interface)
              }
              RETURN count(interface) AS orphanInterfaces
            }
            CALL () {
              MATCH (address:ForwardIpAddress {networkId: $networkId, snapshotId: $snapshotId})
              WHERE NOT EXISTS { MATCH ()-[:HAS_IP_ADDRESS]->(address) }
              RETURN count(address) AS orphanAddresses
            }
            CALL () {
              MATCH (instance:ForwardNetworkInstance {networkId: $networkId, snapshotId: $snapshotId})
              WHERE NOT EXISTS {
                MATCH (:ForwardDevice {networkId: $networkId, snapshotId: $snapshotId})-[:HAS_NETWORK_INSTANCE]->(instance)
              }
              RETURN count(instance) AS orphanNetworkInstances
            }
            CALL () {
              MATCH (neighbor:ForwardBgpNeighbor {networkId: $networkId, snapshotId: $snapshotId})
              WHERE NOT EXISTS { MATCH ()-[:HAS_BGP_NEIGHBOR]->(neighbor) }
              RETURN count(neighbor) AS orphanBgpNeighbors
            }
            CALL () {
              MATCH (left:ForwardInterface {networkId: $networkId, snapshotId: $snapshotId})-[:CONNECTED_TO]->(right:ForwardInterface)
              WHERE right.networkId <> $networkId OR right.snapshotId <> $snapshotId
              RETURN count(*) AS crossSnapshotLinks
            }
            RETURN devices, orphanInterfaces, orphanAddresses, orphanNetworkInstances,
                   orphanBgpNeighbors, crossSnapshotLinks
            """,
            Map.of("networkId", networkId, "snapshotId", snapshotId)
        );

        long devices = number(counts, "devices");
        long orphanInterfaces = number(counts, "orphanInterfaces");
        long orphanAddresses = number(counts, "orphanAddresses");
        long orphanNetworkInstances = number(counts, "orphanNetworkInstances");
        long orphanBgpNeighbors = number(counts, "orphanBgpNeighbors");
        long crossSnapshotLinks = number(counts, "crossSnapshotLinks");
        List<String> errors = new ArrayList<>();
        if (devices == 0) {
            errors.add("snapshot contains no devices");
        }
        if (!missing.isEmpty()) {
            errors.add("required components are not validated: " + String.join(", ", missing));
        }
        addCountError(errors, orphanInterfaces, "orphan interfaces");
        addCountError(errors, orphanAddresses, "orphan IP addresses");
        addCountError(errors, orphanNetworkInstances, "orphan network instances");
        addCountError(errors, orphanBgpNeighbors, "orphan BGP neighbors");
        addCountError(errors, crossSnapshotLinks, "cross-snapshot physical links");
        boolean valid = errors.isEmpty();

        Map<String, Object> parameters = new java.util.HashMap<>();
        parameters.put("networkId", networkId);
        parameters.put("snapshotId", snapshotId);
        parameters.put("state", valid ? "VALIDATED" : "INVALID");
        parameters.put("components", List.copyOf(required));
        parameters.put("errors", List.copyOf(errors));
        parameters.put("devices", devices);
        parameters.put("orphanInterfaces", orphanInterfaces);
        parameters.put("orphanAddresses", orphanAddresses);
        parameters.put("orphanNetworkInstances", orphanNetworkInstances);
        parameters.put("orphanBgpNeighbors", orphanBgpNeighbors);
        parameters.put("crossSnapshotLinks", crossSnapshotLinks);
        one(
            """
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            SET snapshot.validationState = $state,
                snapshot.validatedComponents = $components,
                snapshot.validationErrors = $errors,
                snapshot.validatedDeviceCount = $devices,
                snapshot.orphanInterfaceCount = $orphanInterfaces,
                snapshot.orphanAddressCount = $orphanAddresses,
                snapshot.orphanNetworkInstanceCount = $orphanNetworkInstances,
                snapshot.orphanBgpNeighborCount = $orphanBgpNeighbors,
                snapshot.crossSnapshotLinkCount = $crossSnapshotLinks,
                snapshot.validatedAt = CASE WHEN $state = 'VALIDATED' THEN datetime() ELSE null END
            RETURN snapshot.id AS id
            """,
            parameters
        );

        return new ValidationData(
            networkId,
            snapshotId,
            valid ? "VALIDATED" : "INVALID",
            List.copyOf(required),
            missing,
            List.copyOf(errors),
            devices,
            orphanInterfaces,
            orphanAddresses,
            orphanNetworkInstances,
            orphanBgpNeighbors,
            crossSnapshotLinks
        );
    }

    PublicationData publish(String networkId, String snapshotId) {
        SnapshotState snapshot = snapshot(networkId, snapshotId);
        if ("PUBLISHED".equals(snapshot.publicationState())) {
            if (!snapshotId.equals(snapshot.currentSnapshotId())) {
                throw new IllegalStateException(
                    "Snapshot " + snapshotId + " is marked PUBLISHED but is not the network current snapshot"
                );
            }
            return new PublicationData(
                networkId,
                snapshotId,
                null,
                snapshot.publishedAt(),
                snapshot.validatedComponents()
            );
        }
        if (isPredicted(networkId, snapshotId)) {
            throw new IllegalStateException(
                "Snapshot " + snapshotId + " is a Predict snapshot and can never become the current snapshot"
            );
        }
        if (!"VALIDATED".equals(snapshot.validationState())) {
            throw new IllegalStateException(
                "Snapshot " + snapshotId + " must be VALIDATED before publication; current validation state is " +
                snapshot.validationState()
            );
        }
        Map<String, JobData> jobsByComponent = jobs(networkId, snapshotId)
            .stream()
            .collect(java.util.stream.Collectors.toMap(JobData::component, job -> job));
        for (String component : snapshot.validatedComponents()) {
            JobData job = jobsByComponent.get(component);
            if (job == null || !"VALIDATED".equals(job.state())) {
                throw new IllegalStateException(
                    "Validated component '" + component + "' is missing or no longer VALIDATED"
                );
            }
        }

        Map<String, Object> row = one(
            """
            MATCH (network:ForwardNetwork {id: $networkId})
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            OPTIONAL MATCH (previous:ForwardSnapshot {networkId: $networkId, id: network.currentSnapshotId})
            WITH network, snapshot, previous, network.currentSnapshotId AS previousSnapshotId
            FOREACH (_ IN CASE WHEN previous IS NOT NULL AND previous.id <> snapshot.id THEN [1] ELSE [] END |
              SET previous.publicationState = 'SUPERSEDED', previous.supersededAt = datetime()
            )
            SET network.previousSnapshotId = CASE
                  WHEN previousSnapshotId IS NULL OR previousSnapshotId = $snapshotId THEN network.previousSnapshotId
                  ELSE previousSnapshotId
                END,
                network.currentSnapshotId = $snapshotId,
                network.publishedAt = datetime(),
                snapshot.publicationState = 'PUBLISHED',
                snapshot.publishedAt = datetime()
            REMOVE network.candidateSnapshotId
            RETURN previousSnapshotId, toString(snapshot.publishedAt) AS publishedAt,
                   snapshot.validatedComponents AS components
            """,
            Map.of("networkId", networkId, "snapshotId", snapshotId)
        );
        return new PublicationData(
            networkId,
            snapshotId,
            nullableText(row, "previousSnapshotId"),
            text(row, "publishedAt"),
            stringList(row.get("components"))
        );
    }

    private boolean isPredicted(String networkId, String snapshotId) {
        return Boolean.TRUE.equals(
            one(
                """
                MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
                RETURN coalesce(snapshot.kind = 'PREDICTED', false) AS predicted
                """,
                Map.of("networkId", networkId, "snapshotId", snapshotId)
            ).get("predicted")
        );
    }

    SnapshotState snapshot(String networkId, String snapshotId) {
        Map<String, Object> row = one(
            """
            MATCH (network:ForwardNetwork {id: $networkId})
            MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: $snapshotId})
            RETURN coalesce(snapshot.publicationState, 'STAGED') AS publicationState,
                   coalesce(snapshot.validationState, 'PENDING') AS validationState,
                   coalesce(snapshot.validatedComponents, []) AS validatedComponents,
                   coalesce(snapshot.validationErrors, []) AS validationErrors,
                   network.currentSnapshotId AS currentSnapshotId,
                   network.candidateSnapshotId AS candidateSnapshotId,
                   toString(snapshot.importedAt) AS importedAt,
                   toString(snapshot.validatedAt) AS validatedAt,
                   toString(snapshot.publishedAt) AS publishedAt
            """,
            Map.of("networkId", networkId, "snapshotId", snapshotId)
        );
        return new SnapshotState(
            networkId,
            snapshotId,
            text(row, "publicationState"),
            text(row, "validationState"),
            stringList(row.get("validatedComponents")),
            stringList(row.get("validationErrors")),
            nullableText(row, "currentSnapshotId"),
            nullableText(row, "candidateSnapshotId"),
            nullableText(row, "importedAt"),
            nullableText(row, "validatedAt"),
            nullableText(row, "publishedAt")
        );
    }

    private SnapshotState requireMutableSnapshot(String networkId, String snapshotId) {
        SnapshotState snapshot = snapshot(networkId, snapshotId);
        if ("PUBLISHED".equals(snapshot.publicationState())) {
            throw new IllegalStateException(
                "Snapshot " + snapshotId + " is already published and is immutable; import a new snapshot instead"
            );
        }
        return snapshot;
    }

    private Map<String, Object> requireJob(String networkId, String snapshotId, ImportComponent component) {
        return one(
            """
            MATCH (job:ForwardImportJob {
              networkId: $networkId,
              snapshotId: $snapshotId,
              component: $component
            })
            RETURN job.state AS state, job.attempt AS attempt
            """,
            Map.of("networkId", networkId, "snapshotId", snapshotId, "component", component.id())
        );
    }

    private Map<String, Object> one(String query, Map<String, Object> parameters) {
        try (var rows = transaction.execute(query, parameters)) {
            if (!rows.hasNext()) {
                throw new IllegalStateException("Required Forward graph state was not found");
            }
            return rows.next();
        }
    }

    private static JobData jobData(String networkId, String snapshotId, Map<String, Object> row) {
        return new JobData(
            networkId,
            snapshotId,
            text(row, "component"),
            text(row, "state"),
            number(row, "attempt"),
            nullableNumber(row, "rowsImported"),
            nullableNumber(row, "expectedRows"),
            nullableText(row, "submittedAt"),
            nullableText(row, "startedAt"),
            nullableText(row, "completedAt"),
            nullableText(row, "error")
        );
    }

    private static String sanitizeError(String suppliedError) {
        if (suppliedError == null || suppliedError.isBlank()) {
            return "import failed without an error message";
        }
        String sanitized = suppliedError.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").strip();
        return sanitized.length() <= 2048 ? sanitized : sanitized.substring(0, 2048);
    }

    private static void addCountError(List<String> errors, long count, String label) {
        if (count > 0) {
            errors.add(count + " " + label);
        }
    }

    private static String text(Map<String, Object> row, String key) {
        return Objects.toString(row.get(key), "");
    }

    private static String nullableText(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : value.toString();
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Long nullableNumber(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    record JobData(
        String networkId,
        String snapshotId,
        String component,
        String state,
        long attempt,
        Long rowsImported,
        Long expectedRows,
        String submittedAt,
        String startedAt,
        String completedAt,
        String error
    ) {}

    record ValidationData(
        String networkId,
        String snapshotId,
        String state,
        List<String> requiredComponents,
        List<String> missingComponents,
        List<String> errors,
        long devices,
        long orphanInterfaces,
        long orphanAddresses,
        long orphanNetworkInstances,
        long orphanBgpNeighbors,
        long crossSnapshotLinks
    ) {}

    record PublicationData(
        String networkId,
        String snapshotId,
        String previousSnapshotId,
        String publishedAt,
        List<String> components
    ) {}

    record SnapshotState(
        String networkId,
        String snapshotId,
        String publicationState,
        String validationState,
        List<String> validatedComponents,
        List<String> validationErrors,
        String currentSnapshotId,
        String candidateSnapshotId,
        String importedAt,
        String validatedAt,
        String publishedAt
    ) {}
}
