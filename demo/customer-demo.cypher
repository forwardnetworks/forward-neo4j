// Supply $networkId. Every query resolves through the explicitly published current snapshot.

MATCH (network:ForwardNetwork {id: $networkId})
MATCH (snapshot:ForwardSnapshot {networkId: network.id, id: network.currentSnapshotId})
CALL (snapshot) {
  OPTIONAL MATCH (snapshot)-[:CONTAINS]->(device:ForwardDevice)
  RETURN count(DISTINCT device) AS devices
}
CALL (snapshot) {
  OPTIONAL MATCH (snapshot)-[:HAS_CHECK]->(check:ForwardCheckObservation)
  RETURN count(DISTINCT check) AS checks,
         count(DISTINCT CASE WHEN check.status = "FAIL" THEN check END) AS failingChecks
}
CALL (snapshot) {
  OPTIONAL MATCH (snapshot)-[:HAS_VULNERABILITY]->(vulnerability:ForwardVulnerability)
  RETURN count(DISTINCT vulnerability) AS vulnerabilities
}
RETURN snapshot.id AS publishedSnapshot, snapshot.processedAt AS processedAt,
       snapshot.validatedComponents AS components, devices, checks, failingChecks, vulnerabilities;

MATCH (network:ForwardNetwork {id: $networkId})
MATCH path=(left:ForwardDevice)-[:HAS_INTERFACE]->(:ForwardInterface)
           -[:CONNECTED_TO]-(:ForwardInterface)<-[:HAS_INTERFACE]-(right:ForwardDevice)
WHERE left.networkId = $networkId
  AND left.snapshotId = network.currentSnapshotId
RETURN path;

MATCH (network:ForwardNetwork {id: $networkId})
MATCH (device:ForwardDevice {networkId: $networkId, snapshotId: network.currentSnapshotId})
      -[:HAS_NETWORK_INSTANCE]->(instance:ForwardNetworkInstance)
      -[:HAS_BGP_NEIGHBOR]->(neighbor:ForwardBgpNeighbor)
RETURN device.displayName AS device, instance.name AS networkInstance,
       neighbor.neighborAddress AS neighbor, neighbor.sessionState AS state,
       neighbor.peerDeviceName AS peerDevice
ORDER BY state, device, neighbor;

MATCH (network:ForwardNetwork {id: $networkId})
MATCH (snapshot:ForwardSnapshot {networkId: $networkId, id: network.currentSnapshotId})
      -[:HAS_CHECK]->(check:ForwardCheckObservation)
WHERE check.status = "FAIL"
RETURN check.name AS check, check.priority AS priority, check.numViolations AS violations,
       check.diagnosisSummary AS diagnosis
ORDER BY priority, violations DESC, check;

MATCH (network:ForwardNetwork {id: $networkId})
MATCH (vulnerability:ForwardVulnerability {networkId: $networkId, snapshotId: network.currentSnapshotId})
      -[finding:AFFECTS]->(device:ForwardDevice)
RETURN vulnerability.id AS cve, device.displayName AS device, finding.status AS status,
       finding.result AS result, finding.internetAddressable AS internetAddressable
ORDER BY cve, device;

MATCH (network:ForwardNetwork {id: $networkId})
MATCH (application:ForwardApplication {networkId: $networkId})
      -[:RUNS_ON {snapshotId: network.currentSnapshotId}]->(device:ForwardDevice)
OPTIONAL MATCH (vulnerability:ForwardVulnerability)-[:AFFECTS]->(device)
WHERE vulnerability IS NULL OR
      (vulnerability.networkId = $networkId AND vulnerability.snapshotId = network.currentSnapshotId)
RETURN application.name AS application, application.criticality AS criticality,
       collect(DISTINCT device.displayName) AS devices,
       collect(DISTINCT vulnerability.id) AS affectingCves
ORDER BY criticality, application;

MATCH (network:ForwardNetwork {id: $networkId})
MATCH (application:ForwardApplication {networkId: $networkId})-[:HAS_FLOW]->(flow:ForwardApplicationFlow)
      -[:EVALUATED_AS {snapshotId: network.currentSnapshotId}]->(observation:ForwardPathObservation)
      -[:HAS_PATH]->(result:ForwardPathResult)
OPTIONAL MATCH (result)-[:HAS_HOP]->(hop:ForwardPathHop)
WITH application, flow, result, hop
ORDER BY hop.hopIndex
RETURN application.name AS application, flow.name AS flow,
       result.forwardingOutcome AS forwardingOutcome, result.securityOutcome AS securityOutcome,
       collect(hop.deviceName) AS orderedDevices;
