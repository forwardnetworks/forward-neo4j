CALL forward.vulnerabilities.stream($networkId, $snapshotId)
YIELD networkId, snapshotId, indexCreatedAt, indexUploadedAt, row
CALL (networkId, snapshotId, indexCreatedAt, indexUploadedAt, row) {
  WITH networkId, snapshotId, indexCreatedAt, indexUploadedAt, row
  WHERE row.id IS NOT NULL
  MERGE (network:ForwardNetwork {id: networkId})
  MERGE (snapshot:ForwardSnapshot {networkId: networkId, id: snapshotId})
  SET snapshot.vulnerabilityIndexCreatedAt = indexCreatedAt,
      snapshot.vulnerabilityIndexUploadedAt = indexUploadedAt
  MERGE (network)-[:HAS_SNAPSHOT]->(snapshot)
  MERGE (vulnerability:ForwardVulnerability {networkId: networkId, snapshotId: snapshotId, id: row.id})
  SET vulnerability.description = row.description,
      vulnerability.hasCisaKevEntry = row.hasCisaKevEntry,
      vulnerability.weaknesses = row.weaknesses,
      vulnerability.lastImportedAt = datetime()
  MERGE (snapshot)-[:HAS_VULNERABILITY]->(vulnerability)
  WITH networkId, snapshotId, vulnerability, coalesce(row.osInfos, []) AS osInfos
  UNWIND osInfos AS osInfo
  MERGE (analysis:ForwardVulnerabilityOs {
    networkId: networkId,
    snapshotId: snapshotId,
    cveId: vulnerability.id,
    vendor: osInfo.vendor,
    os: osInfo.os
  })
  SET analysis.severity = osInfo.severity,
      analysis.v2Score = osInfo.v2Score,
      analysis.v3Score = osInfo.v3Score,
      analysis.v4Score = osInfo.v4Score,
      analysis.url = osInfo.url,
      analysis.publishDate = osInfo.publishDate,
      analysis.advisoryMentionsExploit = osInfo.advisoryMentionsExploit,
      analysis.configDependent = osInfo.configDependent,
      analysis.configAnalysis = osInfo.configAnalysis,
      analysis.osVersions = osInfo.osVersions,
      analysis.deviceCount = osInfo.deviceCount,
      analysis.vulnerableDeviceCount = reduce(total = 0, item IN coalesce(osInfo.resultCounts, []) |
        total + CASE WHEN item.result IN ["VULNERABLE", "OS_VULNERABLE"] THEN coalesce(item.deviceCount, 0) ELSE 0 END),
      analysis.unconfirmedDeviceCount = reduce(total = 0, item IN coalesce(osInfo.resultCounts, []) |
        total + CASE WHEN item.result IN ["UNCONFIRMED", "UNIMPLEMENTED"] THEN coalesce(item.deviceCount, 0) ELSE 0 END),
      analysis.notVulnerableDeviceCount = reduce(total = 0, item IN coalesce(osInfo.resultCounts, []) |
        total + CASE WHEN item.result = "NOT_VULNERABLE" THEN coalesce(item.deviceCount, 0) ELSE 0 END),
      analysis.locationIds = osInfo.locationIds,
      analysis.tags = osInfo.tags,
      analysis.lastImportedAt = datetime()
  MERGE (vulnerability)-[:HAS_OS_ANALYSIS]->(analysis)
} IN TRANSACTIONS OF 500 ROWS ON ERROR RETRY FOR 30 SECONDS THEN FAIL
RETURN count(*) AS importedRows;
