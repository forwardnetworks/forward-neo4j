CALL forward.checks.stream($networkId, $snapshotId)
YIELD networkId, snapshotId, row
CALL (networkId, snapshotId, row) {
  WITH networkId, snapshotId, row
  WHERE row.id IS NOT NULL
  MERGE (network:ForwardNetwork {id: networkId})
  MERGE (snapshot:ForwardSnapshot {networkId: networkId, id: snapshotId})
  MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
  MERGE (check:ForwardCheckObservation {networkId: networkId, snapshotId: snapshotId, id: row.id})
  SET check.name = row.name,
      check.description = row.description,
      check.note = row.note,
      check.enabled = row.enabled,
      check.priority = row.priority,
      check.status = row.status,
      check.numViolations = row.numViolations,
      check.executionDurationMillis = row.executionDurationMillis,
      check.createdAt = row.createdAt,
      check.definedAt = row.definedAt,
      check.editedAt = row.editedAt,
      check.executedAt = row.executedAt,
      check.tags = row.tags,
      check.checkType = row.definition.checkType,
      check.lastImportedAt = datetime()
  MERGE (snapshot)-[:HAS_CHECK]->(check)
} IN TRANSACTIONS OF 1000 ROWS ON ERROR RETRY FOR 30 SECONDS THEN FAIL
RETURN count(*) AS importedRows;
