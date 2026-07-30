CALL forward.inventory.topology.stream($networkId, $snapshotId)
YIELD networkId, snapshotId, linkRow, sourceId, sourceDevice, sourceInterface,
      targetId, targetDevice, targetInterface, skipReason
CALL (networkId, snapshotId, linkRow, sourceId, sourceDevice, sourceInterface,
      targetId, targetDevice, targetInterface) {
  WITH * WHERE linkRow
  MATCH (sourceNode:ForwardDevice {
    networkId: networkId, snapshotId: snapshotId, id: sourceDevice
  })
  MATCH (targetNode:ForwardDevice {
    networkId: networkId, snapshotId: snapshotId, id: targetDevice
  })
  MERGE (source:ForwardInterface {networkId: networkId, snapshotId: snapshotId, id: sourceId})
  SET source.name = sourceInterface,
      source.deviceName = sourceDevice,
      source.lastImportedAt = datetime()
  MERGE (target:ForwardInterface {networkId: networkId, snapshotId: snapshotId, id: targetId})
  SET target.name = targetInterface,
      target.deviceName = targetDevice,
      target.lastImportedAt = datetime()
  MERGE (sourceNode)-[:HAS_INTERFACE]->(source)
  MERGE (targetNode)-[:HAS_INTERFACE]->(target)
  MERGE (source)-[:CONNECTED_TO]->(target)
} IN TRANSACTIONS OF 5000 ROWS ON ERROR RETRY FOR 30 SECONDS THEN FAIL
RETURN "INVENTORY_TOPOLOGY=" +
       toString(count(CASE WHEN linkRow THEN 1 END)) + "|" +
       toString(count(CASE WHEN NOT linkRow THEN 1 END)) AS marker;
