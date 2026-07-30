CALL forward.nqe.stream("bgp-neighbors-v1", $networkId, $snapshotId)
YIELD networkId, snapshotId, projection, projectionVersion, projectionSha256, executionKey, resultRow, row
CALL (networkId, snapshotId, row, resultRow) {
  WITH * WHERE resultRow
  MERGE (network:ForwardNetwork {id: networkId})
  MERGE (snapshot:ForwardSnapshot {networkId: networkId, id: snapshotId})
  MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
  MERGE (device:ForwardDevice {networkId: networkId, snapshotId: snapshotId, id: row.deviceName})
  MERGE (snapshot)-[:CONTAINS]->(device)
  MERGE (instance:ForwardNetworkInstance {
    networkId: networkId,
    snapshotId: snapshotId,
    deviceName: row.deviceName,
    name: row.networkInstanceName
  })
  MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
  MERGE (neighbor:ForwardBgpNeighbor {
    networkId: networkId,
    snapshotId: snapshotId,
    deviceName: row.deviceName,
    networkInstanceName: row.networkInstanceName,
    neighborAddress: row.neighborAddress
  })
  SET neighbor.localRouterId = row.localRouterId,
      neighbor.localAsNumber = row.localAsNumber,
      neighbor.peerDeviceName = row.peerDeviceName,
      neighbor.peerVrf = row.peerVrf,
      neighbor.peerRouterId = row.peerRouterId,
      neighbor.sessionState = row.sessionState,
      neighbor.enabled = row.enabled,
      neighbor.description = row.description,
      neighbor.peerAsNumber = row.peerAsNumber,
      neighbor.localAsOverride = row.localAsOverride,
      neighbor.peerType = row.peerType,
      neighbor.sessionDurationSeconds = row.sessionDurationSeconds,
      neighbor.advertisedPrefixes = row.advertisedPrefixes,
      neighbor.receivedPrefixes = row.receivedPrefixes
  MERGE (instance)-[:HAS_BGP_NEIGHBOR]->(neighbor)
  FOREACH (_ IN CASE WHEN row.peerDeviceName IS NOT NULL THEN [1] ELSE [] END |
    MERGE (peer:ForwardDevice {networkId: networkId, snapshotId: snapshotId, id: row.peerDeviceName})
    MERGE (snapshot)-[:CONTAINS]->(peer)
    MERGE (neighbor)-[:RESOLVES_TO_DEVICE]->(peer)
  )
  FOREACH (_ IN CASE WHEN row.peerDeviceName IS NOT NULL AND row.peerVrf IS NOT NULL THEN [1] ELSE [] END |
    MERGE (peerInstance:ForwardNetworkInstance {
      networkId: networkId,
      snapshotId: snapshotId,
      deviceName: row.peerDeviceName,
      name: row.peerVrf
    })
    MERGE (peer:ForwardDevice {networkId: networkId, snapshotId: snapshotId, id: row.peerDeviceName})
    MERGE (peer)-[:HAS_NETWORK_INSTANCE]->(peerInstance)
    MERGE (neighbor)-[:RESOLVES_TO_NETWORK_INSTANCE]->(peerInstance)
  )
} IN TRANSACTIONS OF 5000 ROWS ON ERROR RETRY FOR 30 SECONDS THEN FAIL
WITH networkId, snapshotId, projection, projectionVersion, projectionSha256, executionKey,
     count(row) AS importedRows
MATCH (snapshot:ForwardSnapshot {networkId: networkId, id: snapshotId})
MERGE (run:ForwardProjectionRun {
  networkId: networkId,
  snapshotId: snapshotId,
  name: projection,
  version: projectionVersion,
  sha256: projectionSha256
})
SET run.executionKey = executionKey,
    run.rowsImported = importedRows,
    run.lastImportedAt = datetime()
MERGE (snapshot)-[:HAS_PROJECTION]->(run)
RETURN importedRows;
