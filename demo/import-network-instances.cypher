CALL forward.nqe.stream("network-instances-v1", $networkId, $snapshotId)
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
  SET instance.instanceType = row.instanceType,
      instance.interfaceCount = row.interfaceCount,
      instance.vlanCount = row.vlanCount,
      instance.protocolCount = row.protocolCount,
      instance.ipv4RouteCount = row.ipv4RouteCount,
      instance.ipv6RouteCount = row.ipv6RouteCount
  MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
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
