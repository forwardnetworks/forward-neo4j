CALL forward.schema.install();

MERGE (network:ForwardNetwork {id: "demo-network"})
SET network.displayName = "Synthetic Campus",
    network.candidateSnapshotId = "demo-snapshot",
    network.synthetic = true
MERGE (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
SET snapshot.processedAt = "2026-01-15T12:00:00Z",
    snapshot.importedAt = datetime(),
    snapshot.publicationState = "STAGED",
    snapshot.validationState = "PENDING",
    snapshot.synthetic = true
MERGE (network)-[:HAS_SNAPSHOT]->(snapshot);

UNWIND [
  {id: "edge-a", displayName: "Edge A", type: "ROUTER", vendor: "Example Networks", model: "ER-1000"},
  {id: "edge-b", displayName: "Edge B", type: "ROUTER", vendor: "Example Networks", model: "ER-1000"},
  {id: "core-a", displayName: "Core A", type: "L3_SWITCH", vendor: "Example Networks", model: "CS-9000"},
  {id: "app-a", displayName: "App Leaf", type: "L3_SWITCH", vendor: "Example Networks", model: "LS-5000"},
  {id: "db-a", displayName: "Database Leaf", type: "L3_SWITCH", vendor: "Example Networks", model: "LS-5000"}
] AS item
MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
MERGE (device:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: item.id})
SET device.displayName = item.displayName,
    device.type = item.type,
    device.vendor = item.vendor,
    device.model = item.model,
    device.synthetic = true
MERGE (snapshot)-[:CONTAINS]->(device);

UNWIND [
  {leftDevice: "edge-a", leftPort: "Ethernet1", rightDevice: "core-a", rightPort: "Ethernet1"},
  {leftDevice: "edge-b", leftPort: "Ethernet1", rightDevice: "core-a", rightPort: "Ethernet2"},
  {leftDevice: "core-a", leftPort: "Ethernet3", rightDevice: "app-a", rightPort: "Ethernet1"},
  {leftDevice: "core-a", leftPort: "Ethernet4", rightDevice: "db-a", rightPort: "Ethernet1"}
] AS link
MATCH (left:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: link.leftDevice})
MATCH (right:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: link.rightDevice})
MERGE (leftInterface:ForwardInterface {
  networkId: "demo-network", snapshotId: "demo-snapshot", id: link.leftDevice + " " + link.leftPort
})
SET leftInterface.name = link.leftPort, leftInterface.deviceName = link.leftDevice,
    leftInterface.operStatus = "UP", leftInterface.synthetic = true
MERGE (rightInterface:ForwardInterface {
  networkId: "demo-network", snapshotId: "demo-snapshot", id: link.rightDevice + " " + link.rightPort
})
SET rightInterface.name = link.rightPort, rightInterface.deviceName = link.rightDevice,
    rightInterface.operStatus = "UP", rightInterface.synthetic = true
MERGE (left)-[:HAS_INTERFACE]->(leftInterface)
MERGE (right)-[:HAS_INTERFACE]->(rightInterface)
MERGE (leftInterface)-[:CONNECTED_TO]->(rightInterface);

UNWIND [
  {device: "edge-a", name: "default", routerId: "192.0.2.1", peer: "192.0.2.2", peerDevice: "edge-b", state: "ESTABLISHED"},
  {device: "edge-b", name: "default", routerId: "192.0.2.2", peer: "192.0.2.1", peerDevice: "edge-a", state: "ESTABLISHED"}
] AS item
MATCH (device:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: item.device})
MATCH (peer:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: item.peerDevice})
MERGE (instance:ForwardNetworkInstance {
  networkId: "demo-network", snapshotId: "demo-snapshot", deviceName: item.device, name: item.name
})
SET instance.instanceType = "DEFAULT_INSTANCE", instance.synthetic = true
MERGE (neighbor:ForwardBgpNeighbor {
  networkId: "demo-network", snapshotId: "demo-snapshot", deviceName: item.device,
  networkInstanceName: item.name, neighborAddress: item.peer
})
SET neighbor.localRouterId = item.routerId,
    neighbor.peerDeviceName = item.peerDevice,
    neighbor.sessionState = item.state,
    neighbor.enabled = true,
    neighbor.synthetic = true
MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
MERGE (instance)-[:HAS_BGP_NEIGHBOR]->(neighbor)
MERGE (neighbor)-[:RESOLVES_TO_DEVICE]->(peer);

UNWIND [
  {device: "app-a", interface: "Ethernet1", ip: "198.51.100.10", prefix: 24, vrf: "application"},
  {device: "db-a", interface: "Ethernet1", ip: "203.0.113.20", prefix: 24, vrf: "database"}
] AS item
MATCH (device:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: item.device})
MATCH (interface:ForwardInterface {
  networkId: "demo-network", snapshotId: "demo-snapshot", id: item.device + " " + item.interface
})
MERGE (instance:ForwardNetworkInstance {
  networkId: "demo-network", snapshotId: "demo-snapshot", deviceName: item.device, name: item.vrf
})
SET instance.instanceType = "L3VRF", instance.synthetic = true
MERGE (address:ForwardIpAddress {
  networkId: "demo-network", snapshotId: "demo-snapshot", deviceName: item.device,
  interfaceName: item.interface, subinterfaceName: "", sourceKind: "ROUTED_VLAN",
  ip: item.ip, prefixLength: item.prefix
})
SET address.networkInstanceName = item.vrf, address.synthetic = true
MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
MERGE (interface)-[:HAS_IP_ADDRESS]->(address)
MERGE (address)-[:IN_NETWORK_INSTANCE]->(instance);

UNWIND ["interfaces-v1", "interface-addresses-v1", "network-instances-v1", "bgp-neighbors-v1"] AS name
MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
MERGE (run:ForwardProjectionRun {
  networkId: "demo-network", snapshotId: "demo-snapshot", name: name, version: "1", sha256: "synthetic-demo"
})
SET run.lastImportedAt = datetime(), run.synthetic = true
MERGE (snapshot)-[:HAS_PROJECTION]->(run);

UNWIND [
  {component: "inventory", rows: 9},
  {component: "interfaces-v1", rows: 8},
  {component: "interface-addresses-v1", rows: 2},
  {component: "network-instances-v1", rows: 4},
  {component: "bgp-neighbors-v1", rows: 2},
  {component: "checks", rows: 1},
  {component: "vulnerabilities", rows: 1},
  {component: "paths", rows: 1},
  {component: "business-context", rows: 1}
] AS item
MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
MERGE (job:ForwardImportJob {
  networkId: "demo-network", snapshotId: "demo-snapshot", component: item.component
})
SET job.state = "VALIDATED",
    job.attempt = 1,
    job.rowsImported = item.rows,
    job.expectedRows = item.rows,
    job.submittedAt = datetime(),
    job.startedAt = datetime(),
    job.completedAt = datetime(),
    job.synthetic = true
MERGE (snapshot)-[:HAS_IMPORT_JOB]->(job);

MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
MATCH (edge:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: "edge-a"})
MERGE (check:ForwardCheckObservation {
  networkId: "demo-network", snapshotId: "demo-snapshot", id: "synthetic-edge-redundancy"
})
SET check.name = "Edge redundancy",
    check.checkType = "Existential",
    check.status = "FAIL",
    check.priority = "HIGH",
    check.numViolations = 1,
    check.synthetic = true
MERGE (snapshot)-[:HAS_CHECK]->(check)
MERGE (check)-[:AFFECTS {evidence: "SYNTHETIC"}]->(edge);

MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
MATCH (database:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: "db-a"})
MERGE (vulnerability:ForwardVulnerability {
  networkId: "demo-network", snapshotId: "demo-snapshot", id: "CVE-2099-0001"
})
SET vulnerability.description = "Synthetic operating-system vulnerability for demonstration only.",
    vulnerability.hasCisaKevEntry = false,
    vulnerability.synthetic = true
MERGE (snapshot)-[:HAS_VULNERABILITY]->(vulnerability)
MERGE (analysis:ForwardVulnerabilityOs {
  networkId: "demo-network", snapshotId: "demo-snapshot", cveId: "CVE-2099-0001",
  vendor: "EXAMPLE_NETWORKS", os: "EXAMPLE_OS"
})
SET analysis.severity = "HIGH", analysis.v3Score = 8.1, analysis.deviceCount = 1,
    analysis.vulnerableDeviceCount = 1, analysis.synthetic = true
MERGE (vulnerability)-[:HAS_OS_ANALYSIS]->(analysis)
MERGE (vulnerability)-[finding:AFFECTS]->(database)
SET finding.result = "VULNERABLE", finding.status = "VULNERABLE", finding.synthetic = true;

MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
MATCH (appDevice:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: "app-a"})
MATCH (dbDevice:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: "db-a"})
MERGE (application:ForwardApplication {networkId: "demo-network", id: "sample-checkout"})
SET application.name = "Sample Checkout", application.criticality = "CRITICAL",
    application.source = "SYNTHETIC", application.synthetic = true
MERGE (owner:ForwardOwner {networkId: "demo-network", name: "Demo Engineering"})
SET owner.synthetic = true
MERGE (owner)-[:OWNS]->(application)
MERGE (application)-[:RUNS_ON {snapshotId: "demo-snapshot"}]->(appDevice)
MERGE (application)-[:RUNS_ON {snapshotId: "demo-snapshot"}]->(dbDevice)
MERGE (flow:ForwardApplicationFlow {
  networkId: "demo-network", applicationId: "sample-checkout", name: "sample-checkout-to-database"
})
SET flow.srcIp = "198.51.100.10", flow.dstIp = "203.0.113.20", flow.ipProto = 6,
    flow.dstPort = "5432", flow.synthetic = true
MERGE (application)-[:HAS_FLOW]->(flow)
MERGE (observation:ForwardPathObservation {
  networkId: "demo-network", snapshotId: "demo-snapshot", name: "sample-checkout-to-database"
})
SET observation.srcIp = flow.srcIp, observation.dstIp = flow.dstIp,
    observation.ipProto = flow.ipProto, observation.dstPort = flow.dstPort,
    observation.timedOut = false, observation.error = false, observation.synthetic = true
MERGE (snapshot)-[:HAS_PATH_OBSERVATION]->(observation)
MERGE (flow)-[:EVALUATED_AS {snapshotId: "demo-snapshot"}]->(observation)
MERGE (pathResult:ForwardPathResult {
  networkId: "demo-network", snapshotId: "demo-snapshot", flowName: flow.name,
  direction: "FORWARD", pathIndex: 0
})
SET pathResult.forwardingOutcome = "DELIVERED", pathResult.securityOutcome = "PERMITTED",
    pathResult.synthetic = true
MERGE (observation)-[:HAS_PATH]->(pathResult)
WITH pathResult, appDevice, dbDevice
UNWIND [
  {index: 0, device: appDevice, ingress: "Ethernet1", egress: "Ethernet1"},
  {index: 1, device: dbDevice, ingress: "Ethernet1", egress: "Ethernet1"}
] AS item
WITH pathResult, item, item.device AS hopDevice
MERGE (hop:ForwardPathHop {
  networkId: "demo-network", snapshotId: "demo-snapshot", flowName: "sample-checkout-to-database",
  direction: "FORWARD", pathIndex: 0, hopIndex: item.index
})
SET hop.deviceName = hopDevice.id, hop.ingressInterface = item.ingress,
    hop.egressInterface = item.egress, hop.synthetic = true
MERGE (pathResult)-[:HAS_HOP {index: item.index}]->(hop)
MERGE (hop)-[:OBSERVED_AT]->(hopDevice);

MATCH (left:ForwardPathHop {
  networkId: "demo-network", snapshotId: "demo-snapshot", flowName: "sample-checkout-to-database",
  direction: "FORWARD", pathIndex: 0, hopIndex: 0
})
MATCH (right:ForwardPathHop {
  networkId: "demo-network", snapshotId: "demo-snapshot", flowName: "sample-checkout-to-database",
  direction: "FORWARD", pathIndex: 0, hopIndex: 1
})
MERGE (left)-[:NEXT_HOP]->(right);

CALL forward.snapshot.validate(
  "demo-network",
  "demo-snapshot",
  ["interfaces-v1", "interface-addresses-v1", "network-instances-v1", "bgp-neighbors-v1",
   "checks", "vulnerabilities", "paths", "business-context"]
)
YIELD state
RETURN state;

CALL forward.snapshot.publish("demo-network", "demo-snapshot")
YIELD snapshotId, publishedAt
RETURN snapshotId, publishedAt;

MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
OPTIONAL MATCH (snapshot)-[:CONTAINS]->(device:ForwardDevice)
RETURN snapshot.id AS snapshotId, snapshot.publicationState AS publicationState,
       count(DISTINCT device) AS devices;
