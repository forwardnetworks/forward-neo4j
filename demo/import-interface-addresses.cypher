CALL forward.nqe.stream("interface-addresses-v1", $networkId, $snapshotId)
YIELD networkId, snapshotId, projection, projectionVersion, projectionSha256, executionKey, resultRow, row
CALL (networkId, snapshotId, row, resultRow) {
  WITH * WHERE resultRow
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
      interface.name = row.interfaceName
  MERGE (device)-[:HAS_INTERFACE]->(interface)
  MERGE (address:ForwardIpAddress {
    networkId: networkId,
    snapshotId: snapshotId,
    deviceName: row.deviceName,
    interfaceName: row.interfaceName,
    subinterfaceName: row.subinterfaceName,
    sourceKind: row.sourceKind,
    ip: row.ip,
    prefixLength: row.prefixLength
  })
  SET address.networkInstanceName = row.networkInstanceName
  FOREACH (_ IN CASE WHEN row.sourceKind = "SUBINTERFACE" THEN [1] ELSE [] END |
    MERGE (subinterface:ForwardSubinterface {
      networkId: networkId,
      snapshotId: snapshotId,
      deviceName: row.deviceName,
      interfaceName: row.interfaceName,
      name: row.subinterfaceName
    })
    MERGE (interface)-[:HAS_SUBINTERFACE]->(subinterface)
    MERGE (subinterface)-[:HAS_IP_ADDRESS]->(address)
  )
  FOREACH (_ IN CASE WHEN row.sourceKind <> "SUBINTERFACE" THEN [1] ELSE [] END |
    MERGE (interface)-[:HAS_IP_ADDRESS]->(address)
  )
  FOREACH (_ IN CASE WHEN row.networkInstanceName IS NOT NULL THEN [1] ELSE [] END |
    MERGE (instance:ForwardNetworkInstance {
      networkId: networkId,
      snapshotId: snapshotId,
      deviceName: row.deviceName,
      name: row.networkInstanceName
    })
    MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
    MERGE (address)-[:IN_NETWORK_INSTANCE]->(instance)
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
