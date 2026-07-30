CALL forward.inventory.devices.stream($networkId, $snapshotId)
YIELD networkId, snapshotId, id, displayName, type, vendor, model, platform, osVersion,
      locationId, managementIps, tags
CALL (networkId, snapshotId, id, displayName, type, vendor, model, platform, osVersion,
      locationId, managementIps, tags) {
  MATCH (snapshot:ForwardSnapshot {networkId: networkId, id: snapshotId})
  MERGE (device:ForwardDevice {networkId: networkId, snapshotId: snapshotId, id: id})
  SET device.displayName = displayName,
      device.type = type,
      device.vendor = vendor,
      device.model = model,
      device.platform = platform,
      device.osVersion = osVersion,
      device.locationId = locationId,
      device.managementIps = managementIps,
      device.tags = tags,
      device.lastImportedAt = datetime()
  MERGE (snapshot)-[:CONTAINS]->(device)
} IN TRANSACTIONS OF 5000 ROWS ON ERROR RETRY FOR 30 SECONDS THEN FAIL
RETURN "INVENTORY_DEVICES=" + toString(count(*)) AS marker;
