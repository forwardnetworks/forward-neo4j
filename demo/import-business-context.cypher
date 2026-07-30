CALL forward.import.begin("business-context", $networkId, $snapshotId)
YIELD networkId, snapshotId
WITH networkId, snapshotId, $applications AS applications
CALL (networkId, snapshotId, applications) {
  UNWIND applications AS candidate
  UNWIND coalesce(candidate.deviceNames, []) AS requestedDeviceName
  OPTIONAL MATCH (device:ForwardDevice {
    networkId: networkId,
    snapshotId: snapshotId,
    id: requestedDeviceName
  })
  RETURN [name IN collect(CASE WHEN device IS NULL THEN requestedDeviceName END) WHERE name IS NOT NULL]
         AS missingDeviceNames
}
WITH networkId, snapshotId, applications, missingDeviceNames,
     reduce(uniqueIds = [], candidate IN applications |
       CASE WHEN candidate.id IN uniqueIds THEN uniqueIds ELSE uniqueIds + candidate.id END
     ) AS uniqueApplicationIds
WHERE size(applications) > 0
  AND all(candidate IN applications WHERE candidate.id IS NOT NULL AND candidate.name IS NOT NULL)
  AND size(uniqueApplicationIds) = size(applications)
  AND size(missingDeviceNames) = 0
UNWIND applications AS item
WITH networkId, snapshotId, item
MERGE (application:ForwardApplication {networkId: networkId, id: item.id})
SET application.name = item.name,
    application.criticality = item.criticality,
    application.description = item.description,
    application.source = coalesce(item.source, "OPERATOR"),
    application.lastImportedAt = datetime()
FOREACH (_ IN CASE WHEN item.owner IS NOT NULL THEN [1] ELSE [] END |
  MERGE (owner:ForwardOwner {networkId: networkId, name: item.owner})
  MERGE (owner)-[:OWNS]->(application)
)
FOREACH (deviceName IN coalesce(item.deviceNames, []) |
  // Device existence was validated above; MERGE cannot create a placeholder on valid input.
  MERGE (device:ForwardDevice {networkId: networkId, snapshotId: snapshotId, id: deviceName})
  MERGE (application)-[deployment:RUNS_ON {snapshotId: snapshotId}]->(device)
  SET deployment.lastImportedAt = datetime()
)
WITH networkId, snapshotId, application, item
UNWIND CASE WHEN size(coalesce(item.flows, [])) = 0 THEN [null] ELSE item.flows END AS flowItem
FOREACH (_ IN CASE WHEN flowItem IS NOT NULL AND flowItem.name IS NOT NULL THEN [1] ELSE [] END |
  MERGE (flow:ForwardApplicationFlow {
    networkId: networkId,
    applicationId: application.id,
    name: flowItem.name
  })
  SET flow.from = flowItem.from,
      flow.srcIp = flowItem.srcIp,
      flow.dstIp = flowItem.dstIp,
      flow.ipProto = flowItem.ipProto,
      flow.srcPort = flowItem.srcPort,
      flow.dstPort = flowItem.dstPort,
      flow.description = flowItem.description,
      flow.lastImportedAt = datetime()
  MERGE (application)-[:HAS_FLOW]->(flow)
  MERGE (observation:ForwardPathObservation {
    networkId: networkId,
    snapshotId: snapshotId,
    name: flowItem.name
  })
  MERGE (flow)-[:EVALUATED_AS {snapshotId: snapshotId}]->(observation)
)
WITH networkId, snapshotId, count(DISTINCT application) AS importedRows
CALL forward.import.complete("business-context", networkId, snapshotId, importedRows, -1)
YIELD state
RETURN importedRows, state;
