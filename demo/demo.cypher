CALL forward.plugin.info();
CALL forward.license.features();
CALL forward.schema.install();
SHOW CONSTRAINTS YIELD name WHERE name STARTS WITH "forward_" RETURN name;

// Replace these IDs before running against Forward.
CALL forward.import.snapshot("network-id", "snapshot-id")
YIELD snapshotId, devices, links, skippedLinks, durationMillis
RETURN *;
