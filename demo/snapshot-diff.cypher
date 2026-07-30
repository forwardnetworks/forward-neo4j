// Supply $networkId. These read-only queries compare the published current snapshot
// with network.previousSnapshotId. They return no rows when there is no prior publication.

MATCH (network:ForwardNetwork {id: $networkId})
WHERE network.currentSnapshotId IS NOT NULL AND network.previousSnapshotId IS NOT NULL
MATCH (current:ForwardSnapshot {networkId: $networkId, id: network.currentSnapshotId})
MATCH (previous:ForwardSnapshot {networkId: $networkId, id: network.previousSnapshotId})
CALL (current) {
  OPTIONAL MATCH (current)-[:CONTAINS]->(device:ForwardDevice)
  RETURN collect(DISTINCT device.id) AS currentDevices
}
CALL (previous) {
  OPTIONAL MATCH (previous)-[:CONTAINS]->(device:ForwardDevice)
  RETURN collect(DISTINCT device.id) AS previousDevices
}
RETURN previous.id AS previousSnapshot,
       current.id AS currentSnapshot,
       [id IN currentDevices WHERE NOT id IN previousDevices] AS addedDevices,
       [id IN previousDevices WHERE NOT id IN currentDevices] AS removedDevices;

MATCH (network:ForwardNetwork {id: $networkId})
WHERE network.currentSnapshotId IS NOT NULL AND network.previousSnapshotId IS NOT NULL
MATCH (current:ForwardBgpNeighbor {
  networkId: $networkId,
  snapshotId: network.currentSnapshotId
})
OPTIONAL MATCH (previous:ForwardBgpNeighbor {
  networkId: $networkId,
  snapshotId: network.previousSnapshotId,
  deviceName: current.deviceName,
  networkInstanceName: current.networkInstanceName,
  neighborAddress: current.neighborAddress
})
WHERE previous IS NULL OR previous.sessionState <> current.sessionState
RETURN current.deviceName AS device,
       current.networkInstanceName AS networkInstance,
       current.neighborAddress AS neighbor,
       previous.sessionState AS previousState,
       current.sessionState AS currentState
ORDER BY device, networkInstance, neighbor;

MATCH (network:ForwardNetwork {id: $networkId})
WHERE network.currentSnapshotId IS NOT NULL AND network.previousSnapshotId IS NOT NULL
MATCH (current:ForwardCheckObservation {
  networkId: $networkId,
  snapshotId: network.currentSnapshotId
})
OPTIONAL MATCH (previous:ForwardCheckObservation {
  networkId: $networkId,
  snapshotId: network.previousSnapshotId,
  id: current.id
})
WHERE previous IS NULL OR previous.status <> current.status OR
      previous.numViolations <> current.numViolations
RETURN current.id AS checkId,
       current.name AS check,
       previous.status AS previousStatus,
       current.status AS currentStatus,
       previous.numViolations AS previousViolations,
       current.numViolations AS currentViolations
ORDER BY check;

MATCH (network:ForwardNetwork {id: $networkId})
WHERE network.currentSnapshotId IS NOT NULL AND network.previousSnapshotId IS NOT NULL
MATCH (current:ForwardVulnerability {
  networkId: $networkId,
  snapshotId: network.currentSnapshotId
})
WHERE NOT EXISTS {
  MATCH (:ForwardVulnerability {
    networkId: $networkId,
    snapshotId: network.previousSnapshotId,
    id: current.id
  })
}
RETURN current.id AS newlyObservedCve,
       current.severity AS severity,
       current.affectedDevices AS affectedDevices
ORDER BY severity DESC, newlyObservedCve;
