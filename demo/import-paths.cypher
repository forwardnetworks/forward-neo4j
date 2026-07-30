CALL forward.paths.stream($flows, $pathOptions, $networkId, $snapshotId)
YIELD networkId, snapshotId, index, name, query, result
CALL (networkId, snapshotId, index, name, query, result) {
  MERGE (network:ForwardNetwork {id: networkId})
  MERGE (snapshot:ForwardSnapshot {networkId: networkId, id: snapshotId})
  MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
  MERGE (observation:ForwardPathObservation {networkId: networkId, snapshotId: snapshotId, name: name})
  SET observation.requestIndex = index,
      observation.from = query.from,
      observation.srcIp = query.srcIp,
      observation.dstIp = query.dstIp,
      observation.ipProto = query.ipProto,
      observation.srcPort = query.srcPort,
      observation.dstPort = query.dstPort,
      observation.appId = query.appId,
      observation.userId = query.userId,
      observation.userGroupId = query.userGroupId,
      observation.url = query.url,
      observation.domain = query.domain,
      observation.srcIpLocationType = result.srcIpLocationType,
      observation.dstIpLocationType = result.dstIpLocationType,
      observation.timedOut = result.timedOut,
      observation.queryUrl = result.queryUrl,
      observation.error = coalesce(result.error, false),
      observation.errorMessage = coalesce(result.message, result.errorMessage),
      observation.lastImportedAt = datetime()
  MERGE (snapshot)-[:HAS_PATH_OBSERVATION]->(observation)
  WITH networkId, snapshotId, name, observation, [
    {direction: "FORWARD", info: result.info},
    {direction: "RETURN", info: result.returnPathInfo}
  ] AS directions
  UNWIND directions AS selected
  WITH networkId, snapshotId, name, observation, selected
  WHERE selected.info IS NOT NULL
  WITH networkId, snapshotId, name, observation, selected,
       coalesce(selected.info.paths, []) AS paths
  UNWIND CASE WHEN size(paths) = 0 THEN [] ELSE range(0, size(paths) - 1) END AS pathIndex
  WITH networkId, snapshotId, name, observation, selected, pathIndex, paths[pathIndex] AS path
  MERGE (pathResult:ForwardPathResult {
    networkId: networkId,
    snapshotId: snapshotId,
    flowName: name,
    direction: selected.direction,
    pathIndex: pathIndex
  })
  SET pathResult.forwardingOutcome = path.forwardingOutcome,
      pathResult.securityOutcome = path.securityOutcome,
      pathResult.lastImportedAt = datetime()
  MERGE (observation)-[:HAS_PATH]->(pathResult)
  WITH networkId, snapshotId, name, selected, pathIndex, pathResult,
       coalesce(path.hops, []) AS hops
  UNWIND CASE WHEN size(hops) = 0 THEN [] ELSE range(0, size(hops) - 1) END AS hopIndex
  WITH networkId, snapshotId, name, selected, pathIndex, pathResult, hopIndex, hops[hopIndex] AS hop
  MERGE (pathHop:ForwardPathHop {
    networkId: networkId,
    snapshotId: snapshotId,
    flowName: name,
    direction: selected.direction,
    pathIndex: pathIndex,
    hopIndex: hopIndex
  })
  SET pathHop.deviceName = hop.deviceName,
      pathHop.displayName = hop.displayName,
      pathHop.deviceType = hop.deviceType,
      pathHop.ingressInterface = hop.ingressInterface,
      pathHop.egressInterface = hop.egressInterface,
      pathHop.behaviors = hop.behaviors,
      pathHop.tags = hop.tags,
      pathHop.parseError = hop.parseError,
      pathHop.backfilledFrom = hop.backfilledFrom
  MERGE (pathResult)-[:HAS_HOP {index: hopIndex}]->(pathHop)
  WITH pathResult, pathHop, networkId, snapshotId, hop
  OPTIONAL MATCH (device:ForwardDevice {
    networkId: networkId,
    snapshotId: snapshotId,
    id: hop.deviceName
  })
  FOREACH (_ IN CASE WHEN device IS NULL THEN [] ELSE [1] END |
    MERGE (pathHop)-[:OBSERVED_AT]->(device)
  )
  WITH pathResult, pathHop
  ORDER BY pathHop.hopIndex
  WITH pathResult, collect(pathHop) AS orderedHops
  FOREACH (hopIndex IN CASE WHEN size(orderedHops) < 2 THEN [] ELSE range(0, size(orderedHops) - 2) END |
    FOREACH (left IN [orderedHops[hopIndex]] |
      FOREACH (right IN [orderedHops[hopIndex + 1]] |
        MERGE (left)-[:NEXT_HOP]->(right)
      )
    )
  )
} IN TRANSACTIONS OF 100 ROWS ON ERROR RETRY FOR 30 SECONDS THEN FAIL
RETURN count(*) AS importedRows;
