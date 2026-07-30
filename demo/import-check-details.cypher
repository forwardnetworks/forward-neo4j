UNWIND $checkIds AS requestedCheckId
CALL forward.checks.detail(requestedCheckId, $networkId, $snapshotId)
YIELD networkId, snapshotId, row
CALL (networkId, snapshotId, row) {
  WITH networkId, snapshotId, row
  WHERE row.id IS NOT NULL AND row.diagnosis IS NOT NULL
  MERGE (snapshot:ForwardSnapshot {networkId: networkId, id: snapshotId})
  MERGE (check:ForwardCheckObservation {networkId: networkId, snapshotId: snapshotId, id: row.id})
  SET check.name = row.name,
      check.status = row.status,
      check.numViolations = row.numViolations,
      check.diagnosisSummary = row.diagnosis.summary,
      check.diagnosisDetailsIncomplete = row.diagnosis.detailsIncomplete,
      check.lastDetailImportedAt = datetime()
  MERGE (snapshot)-[:HAS_CHECK]->(check)
  WITH networkId, snapshotId, check, coalesce(row.diagnosis.details, []) AS details
  UNWIND CASE WHEN size(details) = 0 THEN [] ELSE range(0, size(details) - 1) END AS detailIndex
  WITH networkId, snapshotId, check, detailIndex, details[detailIndex] AS detail
  MERGE (diagnosis:ForwardCheckDiagnosisDetail {
    networkId: networkId,
    snapshotId: snapshotId,
    checkId: check.id,
    detailIndex: detailIndex
  })
  SET diagnosis.referenceKeys = [reference IN coalesce(detail.references, [])
        WHERE reference.key IS NOT NULL | reference.key],
      diagnosis.referenceValues = [reference IN coalesce(detail.references, [])
        WHERE reference.value IS NOT NULL | reference.value],
      diagnosis.queryDetail = detail.query IS NOT NULL,
      diagnosis.priority = detail.priority,
      diagnosis.note = detail.note,
      diagnosis.lastImportedAt = datetime()
  MERGE (check)-[:HAS_DIAGNOSIS_DETAIL]->(diagnosis)
  WITH networkId, snapshotId, diagnosis
  OPTIONAL MATCH (device:ForwardDevice {networkId: networkId, snapshotId: snapshotId})
  WHERE device.id IN diagnosis.referenceKeys
  FOREACH (_ IN CASE WHEN device IS NULL THEN [] ELSE [1] END |
    MERGE (diagnosis)-[:REFERENCES]->(device)
  )
} IN TRANSACTIONS OF 100 ROWS ON ERROR RETRY FOR 30 SECONDS THEN FAIL
RETURN count(*) AS importedRows;
