CALL forward.nqe.stream("interfaces-v1", $networkId, $snapshotId)
YIELD networkId, snapshotId, projection, projectionVersion, projectionSha256, executionKey, row
CALL (networkId, snapshotId, projection, projectionVersion, projectionSha256, executionKey, row) {
  MERGE (network:ForwardNetwork {id: networkId})
  MERGE (snapshot:ForwardSnapshot {networkId: networkId, id: snapshotId})
  MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
  MERGE (device:ForwardDevice {networkId: networkId, snapshotId: snapshotId, id: row.deviceName})
  MERGE (snapshot)-[:CONTAINS]->(device)
  MERGE (interface:ForwardInterface {
    networkId: networkId,
    snapshotId: snapshotId,
    id: row.deviceName + " " + row.interfaceName
  })
  SET interface.deviceName = row.deviceName,
      interface.name = row.interfaceName,
      interface.interfaceType = row.interfaceType,
      interface.layer = row.layer,
      interface.mtu = row.mtu,
      interface.loopbackMode = row.loopbackMode,
      interface.description = row.description,
      interface.adminStatus = row.adminStatus,
      interface.operStatus = row.operStatus,
      interface.speedMbps = row.speedMbps,
      interface.aggregateId = row.aggregateId
  MERGE (device)-[:HAS_INTERFACE]->(interface)
  MERGE (run:ForwardProjectionRun {
    networkId: networkId,
    snapshotId: snapshotId,
    name: projection,
    version: projectionVersion,
    sha256: projectionSha256
  })
  SET run.executionKey = executionKey,
      run.lastImportedAt = datetime()
  MERGE (snapshot)-[:HAS_PROJECTION]->(run)
} IN TRANSACTIONS OF 5000 ROWS ON ERROR RETRY FOR 30 SECONDS THEN FAIL
RETURN count(*) AS importedRows;
