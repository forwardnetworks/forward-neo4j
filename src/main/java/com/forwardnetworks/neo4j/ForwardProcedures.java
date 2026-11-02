package com.forwardnetworks.neo4j;

import java.util.Map;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.TerminationGuard;

/** Neo4j procedure entry points for the Forward integration. */
public final class ForwardProcedures {

    static final String VERSION = resolveVersion();

    private static String resolveVersion() {
        String implementationVersion = ForwardProcedures.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank()
            ? "development"
            : implementationVersion;
    }

    @Context
    public Transaction transaction;

    @Context
    public Log log;

    @Context
    public TerminationGuard terminationGuard;

    @Procedure(name = "forward.plugin.info", mode = Mode.READ)
    @Description("Reports the Forward Neo4j plugin version and whether required environment is configured.")
    public Stream<PluginInfoResult> info() {
        var environment = System.getenv();
        var status = ForwardConfig.status(environment, System.getProperties());
        var licenseTier = ForwardConfig.licenseTier(environment, System.getProperties());
        boolean tokenConfigured = environment.containsKey("FORWARD_TOKEN") ||
            System.getProperty("forward.token") != null;
        return Stream.of(
            new PluginInfoResult(
                VERSION,
                status.urlConfigured(),
                tokenConfigured,
                status.credentialsConfigured(),
                status.credentialFileConfigured(),
                status.defaultNetworkConfigured(),
                licenseTier.name(),
                licenseTier.network(),
                licenseTier.security(),
                licenseTier.predict()
            )
        );
    }

    @Procedure(name = "forward.license.features", mode = Mode.READ)
    @Description("Lists integration features enabled by the configured Forward license tier selector.")
    public Stream<LicenseFeatureResult> licenseFeatures() {
        var licenseTier = ForwardConfig.licenseTier(System.getenv(), System.getProperties());
        return Stream.of(ForwardFeature.values()).map(feature -> new LicenseFeatureResult(
            licenseTier.name(),
            feature.id(),
            licenseTier.enables(feature),
            feature.requirement(),
            feature.description()
        ));
    }

    @Procedure(name = "forward.testConnection", mode = Mode.READ)
    @Description("Makes a real read-only Forward Networks API request and reports sanitized connectivity results.")
    public Stream<ConnectionResult> testConnection() {
        long startedAt = System.nanoTime();
        var connection = new ForwardApiClient(ForwardConfig.fromEnvironment()).testConnection();
        long durationMillis = java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        return Stream.of(
            new ConnectionResult(
                true,
                connection.accessibleNetworks(),
                connection.defaultNetworkConfigured(),
                connection.defaultNetworkVisible(),
                durationMillis
            )
        );
    }

    @Procedure(name = "forward.metrics.prometheus", mode = Mode.READ)
    @Description("Exports low-cardinality integration health metrics in Prometheus text format.")
    public Stream<MetricsLineResult> prometheusMetrics(
        @Name(value = "networkId", defaultValue = "") String networkId
    ) {
        return new MetricsCollector(transaction).prometheus(networkId).stream().map(MetricsLineResult::new);
    }

    @Procedure(name = "forward.schema.install", mode = Mode.SCHEMA)
    @Description("Installs the minimal constraints used by Forward snapshot imports.")
    public Stream<SchemaInstallResult> installSchema() {
        var statements = Map.ofEntries(
            Map.entry("network", "CREATE CONSTRAINT forward_network IF NOT EXISTS FOR (n:ForwardNetwork) REQUIRE n.id IS UNIQUE"),
            Map.entry(
                "snapshot",
                "CREATE CONSTRAINT forward_snapshot IF NOT EXISTS FOR (n:ForwardSnapshot) REQUIRE (n.networkId, n.id) IS UNIQUE"
            ),
            Map.entry(
                "device",
                "CREATE CONSTRAINT forward_device IF NOT EXISTS FOR (n:ForwardDevice) REQUIRE (n.networkId, n.snapshotId, n.id) IS UNIQUE"
            ),
            Map.entry(
                "interface",
                "CREATE CONSTRAINT forward_interface IF NOT EXISTS FOR (n:ForwardInterface) REQUIRE (n.networkId, n.snapshotId, n.id) IS UNIQUE"
            ),
            Map.entry(
                "subinterface",
                "CREATE CONSTRAINT forward_subinterface IF NOT EXISTS FOR (n:ForwardSubinterface) REQUIRE (n.networkId, n.snapshotId, n.deviceName, n.interfaceName, n.name) IS UNIQUE"
            ),
            Map.entry(
                "address",
                "CREATE CONSTRAINT forward_ip_address IF NOT EXISTS FOR (n:ForwardIpAddress) REQUIRE (n.networkId, n.snapshotId, n.deviceName, n.interfaceName, n.subinterfaceName, n.sourceKind, n.ip, n.prefixLength) IS UNIQUE"
            ),
            Map.entry(
                "networkInstance",
                "CREATE CONSTRAINT forward_network_instance IF NOT EXISTS FOR (n:ForwardNetworkInstance) REQUIRE (n.networkId, n.snapshotId, n.deviceName, n.name) IS UNIQUE"
            ),
            Map.entry(
                "bgpNeighbor",
                "CREATE CONSTRAINT forward_bgp_neighbor IF NOT EXISTS FOR (n:ForwardBgpNeighbor) REQUIRE (n.networkId, n.snapshotId, n.deviceName, n.networkInstanceName, n.neighborAddress) IS UNIQUE"
            ),
            Map.entry(
                "projectionRun",
                "CREATE CONSTRAINT forward_projection_run IF NOT EXISTS FOR (n:ForwardProjectionRun) REQUIRE (n.networkId, n.snapshotId, n.name, n.version, n.sha256) IS UNIQUE"
            ),
            Map.entry(
                "checkObservation",
                "CREATE CONSTRAINT forward_check_observation IF NOT EXISTS FOR (n:ForwardCheckObservation) REQUIRE (n.networkId, n.snapshotId, n.id) IS UNIQUE"
            ),
            Map.entry(
                "vulnerability",
                "CREATE CONSTRAINT forward_vulnerability IF NOT EXISTS FOR (n:ForwardVulnerability) REQUIRE (n.networkId, n.snapshotId, n.id) IS UNIQUE"
            ),
            Map.entry(
                "vulnerabilityOs",
                "CREATE CONSTRAINT forward_vulnerability_os IF NOT EXISTS FOR (n:ForwardVulnerabilityOs) REQUIRE (n.networkId, n.snapshotId, n.cveId, n.vendor, n.os) IS UNIQUE"
            ),
            Map.entry(
                "pathObservation",
                "CREATE CONSTRAINT forward_path_observation IF NOT EXISTS FOR (n:ForwardPathObservation) REQUIRE (n.networkId, n.snapshotId, n.name) IS UNIQUE"
            ),
            Map.entry(
                "pathResult",
                "CREATE CONSTRAINT forward_path_result IF NOT EXISTS FOR (n:ForwardPathResult) REQUIRE (n.networkId, n.snapshotId, n.flowName, n.direction, n.pathIndex) IS UNIQUE"
            ),
            Map.entry(
                "pathHop",
                "CREATE CONSTRAINT forward_path_hop IF NOT EXISTS FOR (n:ForwardPathHop) REQUIRE (n.networkId, n.snapshotId, n.flowName, n.direction, n.pathIndex, n.hopIndex) IS UNIQUE"
            ),
            Map.entry(
                "importJob",
                "CREATE CONSTRAINT forward_import_job IF NOT EXISTS FOR (n:ForwardImportJob) REQUIRE (n.networkId, n.snapshotId, n.component) IS UNIQUE"
            ),
            Map.entry(
                "application",
                "CREATE CONSTRAINT forward_application IF NOT EXISTS FOR (n:ForwardApplication) REQUIRE (n.networkId, n.id) IS UNIQUE"
            ),
            Map.entry(
                "owner",
                "CREATE CONSTRAINT forward_owner IF NOT EXISTS FOR (n:ForwardOwner) REQUIRE (n.networkId, n.name) IS UNIQUE"
            ),
            Map.entry(
                "applicationFlow",
                "CREATE CONSTRAINT forward_application_flow IF NOT EXISTS FOR (n:ForwardApplicationFlow) REQUIRE (n.networkId, n.applicationId, n.name) IS UNIQUE"
            ),
            Map.entry(
                "checkDiagnosisDetail",
                "CREATE CONSTRAINT forward_check_diagnosis_detail IF NOT EXISTS FOR (n:ForwardCheckDiagnosisDetail) REQUIRE (n.networkId, n.snapshotId, n.checkId, n.detailIndex) IS UNIQUE"
            )
        );
        statements.values().forEach(transaction::execute);
        log.info("Installed %d Forward graph constraints", statements.size());
        return Stream.of(new SchemaInstallResult((long) statements.size()));
    }

    @Procedure(name = "forward.import.snapshot", mode = Mode.WRITE)
    @Description("Imports devices and physical topology from a Forward snapshot using read-only Forward API calls.")
    public Stream<SnapshotImportResult> importSnapshot(
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        long startedAt = System.nanoTime();
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.INVENTORY);
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var client = new ForwardApiClient(config);
        var snapshot = client.selectSnapshot(resolvedNetworkId, snapshotId);
        var devices = client.getDevices(resolvedNetworkId, snapshot.id());
        var topology = client.getTopology(snapshot.id());
        var stats = new SnapshotImporter(transaction).importSnapshot(resolvedNetworkId, snapshot, devices, topology);
        long durationMillis = java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        log.info(
            "Imported Forward snapshot %s for network %s: %d devices, %d links, %d skipped links",
            snapshot.id(),
            resolvedNetworkId,
            stats.devices(),
            stats.links(),
            stats.skippedLinks()
        );
        return Stream.of(
            new SnapshotImportResult(
                resolvedNetworkId,
                snapshot.id(),
                stats.devices(),
                stats.links(),
                stats.skippedLinks(),
                durationMillis
            )
        );
    }

    @Procedure(name = "forward.inventory.begin", mode = Mode.WRITE)
    @Description("Stages a Forward snapshot for a memory-bounded inventory import without publishing it.")
    public Stream<InventoryBeginResult> beginInventory(
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.INVENTORY);
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var snapshot = new ForwardApiClient(config).selectSnapshot(resolvedNetworkId, snapshotId);
        var importer = new SnapshotImporter(transaction);
        boolean alreadyPublished = importer.isCurrentPublished(resolvedNetworkId, snapshot.id());
        if (!alreadyPublished) {
            importer.beginInventory(resolvedNetworkId, snapshot);
        }
        return Stream.of(new InventoryBeginResult(
            resolvedNetworkId,
            snapshot.id(),
            snapshot.createdAt(),
            snapshot.processedAt(),
            alreadyPublished
        ));
    }

    @Procedure(name = "forward.inventory.devices.stream", mode = Mode.READ)
    @Description("Incrementally parses and streams devices from the read-only Forward Devices API.")
    public Stream<InventoryDeviceResult> streamInventoryDevices(
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.INVENTORY);
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var client = new ForwardApiClient(config);
        var snapshot = client.selectSnapshot(resolvedNetworkId, snapshotId);
        return client.streamDevices(resolvedNetworkId, snapshot.id(), terminationGuard::check).map(device ->
            new InventoryDeviceResult(
                resolvedNetworkId,
                snapshot.id(),
                device.name(),
                device.displayName(),
                device.type(),
                device.vendor(),
                device.model(),
                device.platform(),
                device.osVersion(),
                device.locationId(),
                device.managementIps(),
                device.tags()
            )
        );
    }

    @Procedure(name = "forward.inventory.topology.stream", mode = Mode.READ)
    @Description("Incrementally parses Forward topology and resolves canonical snapshot-scoped interface links.")
    public Stream<InventoryTopologyResult> streamInventoryTopology(
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.INVENTORY);
        List<String> deviceNames = new SnapshotImporter(transaction).deviceNames(networkId, snapshotId);
        if (deviceNames.isEmpty()) {
            throw new IllegalStateException("Import devices before streaming topology for snapshot " + snapshotId);
        }
        var client = new ForwardApiClient(config);
        return client.streamTopology(snapshotId, terminationGuard::check).map(link -> {
            var source = SnapshotImporter.parsePort(link.sourcePort(), deviceNames);
            var target = SnapshotImporter.parsePort(link.targetPort(), deviceNames);
            if (source == null || target == null) {
                return InventoryTopologyResult.skipped(networkId, snapshotId, "UNRESOLVED_DEVICE");
            }
            if (source.equals(target)) {
                return InventoryTopologyResult.skipped(networkId, snapshotId, "SELF_LINK");
            }
            var canonical = SnapshotImporter.LinkKey.canonical(source, target);
            return InventoryTopologyResult.link(networkId, snapshotId, canonical.source(), canonical.target());
        });
    }

    @Procedure(name = "forward.inventory.complete", mode = Mode.WRITE)
    @Description("Validates a streamed inventory from graph counts and records skipped topology rows.")
    public Stream<SnapshotImportResult> completeInventory(
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId,
        @Name(value = "skippedLinks", defaultValue = "0") long skippedLinks
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        ForwardConfig.licenseTier(System.getenv(), System.getProperties()).require(ForwardFeature.INVENTORY);
        long startedAt = System.nanoTime();
        var stats = new SnapshotImporter(transaction).completeInventory(networkId, snapshotId, skippedLinks);
        return Stream.of(new SnapshotImportResult(
            networkId,
            snapshotId,
            stats.devices(),
            stats.links(),
            stats.skippedLinks(),
            java.time.Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
        ));
    }

    @Procedure(name = "forward.import.begin", mode = Mode.WRITE)
    @Description("Starts or restarts a snapshot-scoped component import job without advancing the published snapshot.")
    public Stream<ImportJobResult> beginImport(
        @Name("component") String componentName,
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        var component = ImportComponent.named(componentName);
        ForwardConfig.licenseTier(System.getenv(), System.getProperties()).require(component.feature());
        return Stream.of(importJobResult(new ImportJobManager(transaction).begin(networkId, snapshotId, component)));
    }

    @Procedure(name = "forward.import.submit", mode = Mode.WRITE)
    @Description("Registers a component import as submitted without starting graph writes or advancing publication.")
    public Stream<ImportJobResult> submitImport(
        @Name("component") String componentName,
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        var component = ImportComponent.named(componentName);
        ForwardConfig.licenseTier(System.getenv(), System.getProperties()).require(component.feature());
        return Stream.of(importJobResult(new ImportJobManager(transaction).submit(networkId, snapshotId, component)));
    }

    @Procedure(name = "forward.import.complete", mode = Mode.WRITE)
    @Description("Completes a component import and validates its streamed row count when an expected count is supplied.")
    public Stream<ImportJobResult> completeImport(
        @Name("component") String componentName,
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId,
        @Name("rowsImported") long rowsImported,
        @Name(value = "expectedRows", defaultValue = "-1") long expectedRows
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        var component = ImportComponent.named(componentName);
        ForwardConfig.licenseTier(System.getenv(), System.getProperties()).require(component.feature());
        return Stream.of(importJobResult(
            new ImportJobManager(transaction).complete(
                networkId,
                snapshotId,
                component,
                rowsImported,
                expectedRows
            )
        ));
    }

    @Procedure(name = "forward.import.fail", mode = Mode.WRITE)
    @Description("Marks a component import failed so an incomplete snapshot cannot be published.")
    public Stream<ImportJobResult> failImport(
        @Name("component") String componentName,
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId,
        @Name("error") String error
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        var component = ImportComponent.named(componentName);
        return Stream.of(importJobResult(
            new ImportJobManager(transaction).fail(networkId, snapshotId, component, error)
        ));
    }

    @Procedure(name = "forward.import.status", mode = Mode.READ)
    @Description("Lists component import states and row-count validation for one snapshot.")
    public Stream<ImportJobResult> importStatus(
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        return new ImportJobManager(transaction).jobs(networkId, snapshotId).stream().map(ForwardProcedures::importJobResult);
    }

    @Procedure(name = "forward.snapshot.validate", mode = Mode.WRITE)
    @Description("Validates required component jobs and graph structure without publishing the snapshot.")
    public Stream<SnapshotValidationResult> validateSnapshot(
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId,
        @Name("requiredComponents") List<String> requiredComponentNames
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        if (requiredComponentNames == null) {
            throw new IllegalArgumentException("requiredComponents must be a list; inventory is always required");
        }
        var tier = ForwardConfig.licenseTier(System.getenv(), System.getProperties());
        List<ImportComponent> components = requiredComponentNames.stream().map(ImportComponent::named).distinct().toList();
        components.forEach(component -> tier.require(component.feature()));
        var validation = new ImportJobManager(transaction).validate(networkId, snapshotId, components);
        return Stream.of(new SnapshotValidationResult(validation));
    }

    @Procedure(name = "forward.snapshot.publish", mode = Mode.WRITE)
    @Description("Atomically advances the current-snapshot pointer to a structurally validated snapshot.")
    public Stream<SnapshotPublicationResult> publishSnapshot(
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        return Stream.of(new SnapshotPublicationResult(
            new ImportJobManager(transaction).publish(networkId, snapshotId)
        ));
    }

    @Procedure(name = "forward.snapshot.status", mode = Mode.READ)
    @Description("Reports staged, validated, and published state for one imported snapshot.")
    public Stream<SnapshotStatusResult> snapshotStatus(
        @Name("networkId") String networkId,
        @Name("snapshotId") String snapshotId
    ) {
        requireIdentifier(networkId, "networkId");
        requireIdentifier(snapshotId, "snapshotId");
        return Stream.of(new SnapshotStatusResult(new ImportJobManager(transaction).snapshot(networkId, snapshotId)));
    }

    @Procedure(name = "forward.snapshot.retention.preview", mode = Mode.READ)
    @Description("Previews snapshot retention without deleting data; current, previous, and candidate are always protected.")
    public Stream<SnapshotRetentionResult> previewSnapshotRetention(
        @Name("networkId") String networkId,
        @Name(value = "keepPublished", defaultValue = "2") long keepPublished,
        @Name(value = "minimumAgeDays", defaultValue = "30") long minimumAgeDays
    ) {
        requireIdentifier(networkId, "networkId");
        return new SnapshotRetentionManager(transaction)
            .preview(networkId, keepPublished, minimumAgeDays)
            .stream()
            .map(SnapshotRetentionResult::new);
    }

    @Procedure(name = "forward.snapshot.prune", mode = Mode.WRITE)
    @Description("Deletes only explicitly named, managed snapshots and refuses protected snapshot pointers.")
    public Stream<SnapshotPruneResult> pruneSnapshots(
        @Name("networkId") String networkId,
        @Name("snapshotIds") List<String> snapshotIds
    ) {
        requireIdentifier(networkId, "networkId");
        return Stream.of(new SnapshotPruneResult(
            new SnapshotRetentionManager(transaction).prune(networkId, snapshotIds)
        ));
    }

    @Procedure(name = "forward.nqe.projections", mode = Mode.READ)
    @Description("Lists the fixed, versioned NQE projections bundled with this plugin.")
    public Stream<NqeProjectionResult> nqeProjections() {
        var licenseTier = ForwardConfig.licenseTier(System.getenv(), System.getProperties());
        return Stream.of(NqeProjection.values()).map(projection ->
            new NqeProjectionResult(
                projection.projectionName(),
                projection.version(),
                projection.sha256(),
                licenseTier.enables(projection.feature()),
                projection.feature().requirement()
            )
        );
    }

    @Procedure(name = "forward.nqe.stream", mode = Mode.READ)
    @Description("Runs a fixed bundled NQE asynchronously and lazily streams its snapshot-scoped result rows.")
    public Stream<NqeRowResult> streamNqe(
        @Name("projection") String projectionName,
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        var config = ForwardConfig.fromEnvironment();
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var projection = NqeProjection.named(projectionName);
        config.licenseTier().require(projection.feature());
        var client = new ForwardApiClient(config);
        var snapshot = client.selectSnapshot(resolvedNetworkId, snapshotId);
        var execution = client.executeNqe(
            resolvedNetworkId,
            snapshot.id(),
            projection,
            terminationGuard::check
        );
        log.info(
            "Streaming Forward NQE projection %s version %s for snapshot %s (%d rows reported)",
            projection.projectionName(),
            projection.version(),
            snapshot.id(),
            execution.rowsProduced()
        );
        var iterator = client.nqeRows(
            resolvedNetworkId,
            snapshot.id(),
            execution.key(),
            terminationGuard::check
        );
        if (!iterator.hasNext()) {
            return Stream.of(
                new NqeRowResult(
                    resolvedNetworkId,
                    snapshot.id(),
                    projection.projectionName(),
                    projection.version(),
                    projection.sha256(),
                    execution.key(),
                    false,
                    null
                )
            );
        }
        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL),
            false
        ).map(row ->
            new NqeRowResult(
                resolvedNetworkId,
                snapshot.id(),
                projection.projectionName(),
                projection.version(),
                projection.sha256(),
                execution.key(),
                true,
                row
            )
        );
    }

    @Procedure(name = "forward.checks.stream", mode = Mode.READ)
    @Description("Streams snapshot-scoped check status observations from the read-only Forward Checks API.")
    public Stream<DirectApiRowResult> streamChecks(
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.CHECKS);
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var client = new ForwardApiClient(config);
        var snapshot = client.selectSnapshot(resolvedNetworkId, snapshotId);
        terminationGuard.check();
        return client.getChecks(snapshot.id())
            .stream()
            .map(row -> new DirectApiRowResult(resolvedNetworkId, snapshot.id(), row));
    }

    @Procedure(name = "forward.checks.detail", mode = Mode.READ)
    @Description("Gets one snapshot-scoped check with diagnosis from the read-only Forward Checks API.")
    public Stream<DirectApiRowResult> checkDetail(
        @Name("checkId") String checkId,
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        requireIdentifier(checkId, "checkId");
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.CHECKS);
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var client = new ForwardApiClient(config);
        var snapshot = client.selectSnapshot(resolvedNetworkId, snapshotId);
        terminationGuard.check();
        return Stream.of(new DirectApiRowResult(
            resolvedNetworkId,
            snapshot.id(),
            client.getCheck(snapshot.id(), checkId)
        ));
    }

    @Procedure(name = "forward.vulnerabilities.stream", mode = Mode.READ)
    @Description("Streams snapshot-scoped CVE summaries from the read-only Forward Vulnerability Analysis API.")
    public Stream<VulnerabilityRowResult> streamVulnerabilities(
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.VULNERABILITIES);
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var client = new ForwardApiClient(config);
        var snapshot = client.selectSnapshot(resolvedNetworkId, snapshotId);
        terminationGuard.check();
        var analysis = client.getVulnerabilities(resolvedNetworkId, snapshot.id());
        return analysis.vulnerabilities()
            .stream()
            .map(row -> new VulnerabilityRowResult(
                resolvedNetworkId,
                snapshot.id(),
                analysis.indexCreatedAt(),
                analysis.indexUploadedAt(),
                row
            ));
    }

    @Procedure(name = "forward.vulnerabilities.detail", mode = Mode.READ)
    @Description("Gets device-level details for one CVE from the read-only Forward Vulnerability Analysis API.")
    public Stream<DirectApiRowResult> vulnerabilityDetail(
        @Name("cveId") String cveId,
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        requireIdentifier(cveId, "cveId");
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.VULNERABILITIES);
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var client = new ForwardApiClient(config);
        var snapshot = client.selectSnapshot(resolvedNetworkId, snapshotId);
        terminationGuard.check();
        return Stream.of(new DirectApiRowResult(
            resolvedNetworkId,
            snapshot.id(),
            client.getVulnerability(resolvedNetworkId, snapshot.id(), cveId)
        ));
    }

    @Procedure(name = "forward.paths.stream", mode = Mode.READ)
    @Description("Runs curated Bulk Path Search queries and streams ordered snapshot-scoped results.")
    public Stream<PathSearchRowResult> streamPaths(
        @Name("flows") java.util.List<Map<String, Object>> flows,
        @Name("options") Map<String, Object> options,
        @Name(value = "networkId", defaultValue = "") String networkId,
        @Name(value = "snapshotId", defaultValue = "") String snapshotId
    ) {
        var config = ForwardConfig.fromEnvironment();
        config.licenseTier().require(ForwardFeature.PATHS);
        String resolvedNetworkId = config.resolveNetworkId(networkId);
        var client = new ForwardApiClient(config);
        var snapshot = client.selectSnapshot(resolvedNetworkId, snapshotId);
        terminationGuard.check();
        return client.getPathsBulk(resolvedNetworkId, snapshot.id(), flows, options)
            .stream()
            .map(item -> new PathSearchRowResult(
                resolvedNetworkId,
                snapshot.id(),
                item.index(),
                item.name(),
                item.query(),
                item.result()
            ));
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static ImportJobResult importJobResult(ImportJobManager.JobData job) {
        return new ImportJobResult(
            job.networkId(),
            job.snapshotId(),
            job.component(),
            job.state(),
            job.attempt(),
            job.rowsImported(),
            job.expectedRows(),
            job.submittedAt(),
            job.startedAt(),
            job.completedAt(),
            job.error()
        );
    }

    public static final class PluginInfoResult {
        public final String version;
        public final boolean forwardUrlConfigured;
        public final boolean forwardTokenConfigured;
        public final boolean forwardCredentialsConfigured;
        public final boolean forwardCredentialFileConfigured;
        public final boolean forwardDefaultNetworkConfigured;
        public final String configuredLicenseTier;
        public final boolean networkFacetEnabled;
        public final boolean securityFacetEnabled;
        public final boolean predictFacetEnabled;

        PluginInfoResult(
            String version,
            boolean forwardUrlConfigured,
            boolean forwardTokenConfigured,
            boolean forwardCredentialsConfigured,
            boolean forwardCredentialFileConfigured,
            boolean forwardDefaultNetworkConfigured,
            String configuredLicenseTier,
            boolean networkFacetEnabled,
            boolean securityFacetEnabled,
            boolean predictFacetEnabled
        ) {
            this.version = version;
            this.forwardUrlConfigured = forwardUrlConfigured;
            this.forwardTokenConfigured = forwardTokenConfigured;
            this.forwardCredentialsConfigured = forwardCredentialsConfigured;
            this.forwardCredentialFileConfigured = forwardCredentialFileConfigured;
            this.forwardDefaultNetworkConfigured = forwardDefaultNetworkConfigured;
            this.configuredLicenseTier = configuredLicenseTier;
            this.networkFacetEnabled = networkFacetEnabled;
            this.securityFacetEnabled = securityFacetEnabled;
            this.predictFacetEnabled = predictFacetEnabled;
        }
    }

    public static final class MetricsLineResult {
        public final String line;

        MetricsLineResult(String line) {
            this.line = line;
        }
    }

    public static final class InventoryBeginResult {
        public final String networkId;
        public final String snapshotId;
        public final String createdAt;
        public final String processedAt;
        public final boolean alreadyPublished;

        InventoryBeginResult(
            String networkId,
            String snapshotId,
            String createdAt,
            String processedAt,
            boolean alreadyPublished
        ) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.createdAt = createdAt;
            this.processedAt = processedAt;
            this.alreadyPublished = alreadyPublished;
        }
    }

    public static final class InventoryDeviceResult {
        public final String networkId;
        public final String snapshotId;
        public final String id;
        public final String displayName;
        public final String type;
        public final String vendor;
        public final String model;
        public final String platform;
        public final String osVersion;
        public final String locationId;
        public final List<String> managementIps;
        public final List<String> tags;

        InventoryDeviceResult(
            String networkId,
            String snapshotId,
            String id,
            String displayName,
            String type,
            String vendor,
            String model,
            String platform,
            String osVersion,
            String locationId,
            List<String> managementIps,
            List<String> tags
        ) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.id = id;
            this.displayName = displayName;
            this.type = type;
            this.vendor = vendor;
            this.model = model;
            this.platform = platform;
            this.osVersion = osVersion;
            this.locationId = locationId;
            this.managementIps = managementIps;
            this.tags = tags;
        }
    }

    public static final class InventoryTopologyResult {
        public final String networkId;
        public final String snapshotId;
        public final boolean linkRow;
        public final String sourceId;
        public final String sourceDevice;
        public final String sourceInterface;
        public final String targetId;
        public final String targetDevice;
        public final String targetInterface;
        public final String skipReason;

        private InventoryTopologyResult(
            String networkId,
            String snapshotId,
            boolean linkRow,
            String sourceId,
            String sourceDevice,
            String sourceInterface,
            String targetId,
            String targetDevice,
            String targetInterface,
            String skipReason
        ) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.linkRow = linkRow;
            this.sourceId = sourceId;
            this.sourceDevice = sourceDevice;
            this.sourceInterface = sourceInterface;
            this.targetId = targetId;
            this.targetDevice = targetDevice;
            this.targetInterface = targetInterface;
            this.skipReason = skipReason;
        }

        static InventoryTopologyResult skipped(String networkId, String snapshotId, String reason) {
            return new InventoryTopologyResult(
                networkId, snapshotId, false, null, null, null, null, null, null, reason
            );
        }

        static InventoryTopologyResult link(
            String networkId,
            String snapshotId,
            SnapshotImporter.PortRef source,
            SnapshotImporter.PortRef target
        ) {
            return new InventoryTopologyResult(
                networkId,
                snapshotId,
                true,
                source.raw(),
                source.deviceName(),
                source.interfaceName(),
                target.raw(),
                target.deviceName(),
                target.interfaceName(),
                null
            );
        }
    }

    public static final class LicenseFeatureResult {
        public final String configuredLicenseTier;
        public final String feature;
        public final boolean enabled;
        public final String requiredFacet;
        public final String description;

        LicenseFeatureResult(
            String configuredLicenseTier,
            String feature,
            boolean enabled,
            String requiredFacet,
            String description
        ) {
            this.configuredLicenseTier = configuredLicenseTier;
            this.feature = feature;
            this.enabled = enabled;
            this.requiredFacet = requiredFacet;
            this.description = description;
        }
    }

    public static final class ConnectionResult {
        public final boolean authenticated;
        public final long accessibleNetworks;
        public final boolean defaultNetworkConfigured;
        public final boolean defaultNetworkVisible;
        public final long durationMillis;

        ConnectionResult(
            boolean authenticated,
            long accessibleNetworks,
            boolean defaultNetworkConfigured,
            boolean defaultNetworkVisible,
            long durationMillis
        ) {
            this.authenticated = authenticated;
            this.accessibleNetworks = accessibleNetworks;
            this.defaultNetworkConfigured = defaultNetworkConfigured;
            this.defaultNetworkVisible = defaultNetworkVisible;
            this.durationMillis = durationMillis;
        }
    }

    public static final class SchemaInstallResult {
        public final long constraintsInstalled;

        SchemaInstallResult(long constraintsInstalled) {
            this.constraintsInstalled = constraintsInstalled;
        }
    }

    public static final class SnapshotImportResult {
        public final String networkId;
        public final String snapshotId;
        public final long devices;
        public final long links;
        public final long skippedLinks;
        public final long durationMillis;

        SnapshotImportResult(
            String networkId,
            String snapshotId,
            long devices,
            long links,
            long skippedLinks,
            long durationMillis
        ) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.devices = devices;
            this.links = links;
            this.skippedLinks = skippedLinks;
            this.durationMillis = durationMillis;
        }
    }

    public static final class ImportJobResult {
        public final String networkId;
        public final String snapshotId;
        public final String component;
        public final String state;
        public final long attempt;
        public final Long rowsImported;
        public final Long expectedRows;
        public final String submittedAt;
        public final String startedAt;
        public final String completedAt;
        public final String error;

        ImportJobResult(
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
        ) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.component = component;
            this.state = state;
            this.attempt = attempt;
            this.rowsImported = rowsImported;
            this.expectedRows = expectedRows;
            this.submittedAt = submittedAt;
            this.startedAt = startedAt;
            this.completedAt = completedAt;
            this.error = error;
        }
    }

    public static final class SnapshotValidationResult {
        public final String networkId;
        public final String snapshotId;
        public final String state;
        public final List<String> requiredComponents;
        public final List<String> missingComponents;
        public final List<String> errors;
        public final long devices;
        public final long orphanInterfaces;
        public final long orphanAddresses;
        public final long orphanNetworkInstances;
        public final long orphanBgpNeighbors;
        public final long crossSnapshotLinks;

        SnapshotValidationResult(ImportJobManager.ValidationData validation) {
            this.networkId = validation.networkId();
            this.snapshotId = validation.snapshotId();
            this.state = validation.state();
            this.requiredComponents = validation.requiredComponents();
            this.missingComponents = validation.missingComponents();
            this.errors = validation.errors();
            this.devices = validation.devices();
            this.orphanInterfaces = validation.orphanInterfaces();
            this.orphanAddresses = validation.orphanAddresses();
            this.orphanNetworkInstances = validation.orphanNetworkInstances();
            this.orphanBgpNeighbors = validation.orphanBgpNeighbors();
            this.crossSnapshotLinks = validation.crossSnapshotLinks();
        }
    }

    public static final class SnapshotPublicationResult {
        public final String networkId;
        public final String snapshotId;
        public final String previousSnapshotId;
        public final String publishedAt;
        public final List<String> components;

        SnapshotPublicationResult(ImportJobManager.PublicationData publication) {
            this.networkId = publication.networkId();
            this.snapshotId = publication.snapshotId();
            this.previousSnapshotId = publication.previousSnapshotId();
            this.publishedAt = publication.publishedAt();
            this.components = publication.components();
        }
    }

    public static final class SnapshotRetentionResult {
        public final String networkId;
        public final String snapshotId;
        public final String publicationState;
        public final String importedAt;
        public final String publishedAt;
        public final boolean protectedSnapshot;
        public final boolean eligible;
        public final String reason;

        SnapshotRetentionResult(SnapshotRetentionManager.RetentionCandidate candidate) {
            this.networkId = candidate.networkId();
            this.snapshotId = candidate.snapshotId();
            this.publicationState = candidate.publicationState();
            this.importedAt = candidate.importedAt();
            this.publishedAt = candidate.publishedAt();
            this.protectedSnapshot = candidate.protectedSnapshot();
            this.eligible = candidate.eligible();
            this.reason = candidate.reason();
        }
    }

    public static final class SnapshotPruneResult {
        public final String networkId;
        public final List<String> snapshotIds;
        public final long snapshotsDeleted;
        public final long nodesDeleted;

        SnapshotPruneResult(SnapshotRetentionManager.PruneResult result) {
            this.networkId = result.networkId();
            this.snapshotIds = result.snapshotIds();
            this.snapshotsDeleted = result.snapshotIds().size();
            this.nodesDeleted = result.deletedNodes();
        }
    }

    public static final class SnapshotStatusResult {
        public final String networkId;
        public final String snapshotId;
        public final String publicationState;
        public final String validationState;
        public final List<String> validatedComponents;
        public final List<String> validationErrors;
        public final String currentSnapshotId;
        public final String candidateSnapshotId;
        public final String importedAt;
        public final String validatedAt;
        public final String publishedAt;
        public final String kind;
        public final String parentSnapshotId;
        public final String changeSetId;

        SnapshotStatusResult(ImportJobManager.SnapshotState snapshot) {
            this.networkId = snapshot.networkId();
            this.snapshotId = snapshot.snapshotId();
            this.publicationState = snapshot.publicationState();
            this.validationState = snapshot.validationState();
            this.validatedComponents = snapshot.validatedComponents();
            this.validationErrors = snapshot.validationErrors();
            this.currentSnapshotId = snapshot.currentSnapshotId();
            this.candidateSnapshotId = snapshot.candidateSnapshotId();
            this.importedAt = snapshot.importedAt();
            this.validatedAt = snapshot.validatedAt();
            this.publishedAt = snapshot.publishedAt();
            this.kind = snapshot.kind();
            this.parentSnapshotId = snapshot.parentSnapshotId();
            this.changeSetId = snapshot.changeSetId();
        }
    }

    public static final class NqeProjectionResult {
        public final String name;
        public final String version;
        public final String sha256;
        public final boolean enabled;
        public final String requiredFacet;

        NqeProjectionResult(String name, String version, String sha256, boolean enabled, String requiredFacet) {
            this.name = name;
            this.version = version;
            this.sha256 = sha256;
            this.enabled = enabled;
            this.requiredFacet = requiredFacet;
        }
    }

    public static final class NqeRowResult {
        public final String networkId;
        public final String snapshotId;
        public final String projection;
        public final String projectionVersion;
        public final String projectionSha256;
        public final String executionKey;
        public final boolean resultRow;
        public final Map<String, Object> row;

        NqeRowResult(
            String networkId,
            String snapshotId,
            String projection,
            String projectionVersion,
            String projectionSha256,
            String executionKey,
            boolean resultRow,
            Map<String, Object> row
        ) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.projection = projection;
            this.projectionVersion = projectionVersion;
            this.projectionSha256 = projectionSha256;
            this.executionKey = executionKey;
            this.resultRow = resultRow;
            this.row = row;
        }
    }

    public static final class DirectApiRowResult {
        public final String networkId;
        public final String snapshotId;
        public final Map<String, Object> row;

        DirectApiRowResult(String networkId, String snapshotId, Map<String, Object> row) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.row = row;
        }
    }

    public static final class VulnerabilityRowResult {
        public final String networkId;
        public final String snapshotId;
        public final String indexCreatedAt;
        public final String indexUploadedAt;
        public final Map<String, Object> row;

        VulnerabilityRowResult(
            String networkId,
            String snapshotId,
            String indexCreatedAt,
            String indexUploadedAt,
            Map<String, Object> row
        ) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.indexCreatedAt = indexCreatedAt;
            this.indexUploadedAt = indexUploadedAt;
            this.row = row;
        }
    }

    public static final class PathSearchRowResult {
        public final String networkId;
        public final String snapshotId;
        public final long index;
        public final String name;
        public final Map<String, Object> query;
        public final Map<String, Object> result;

        PathSearchRowResult(
            String networkId,
            String snapshotId,
            long index,
            String name,
            Map<String, Object> query,
            Map<String, Object> result
        ) {
            this.networkId = networkId;
            this.snapshotId = snapshotId;
            this.index = index;
            this.name = name;
            this.query = query;
            this.result = result;
        }
    }
}
