CALL forward.schema.install();

MERGE (network:ForwardNetwork {id: "demo-network"})
SET network.displayName = "Synthetic Campus",
    network.currentSnapshotId = "demo-snapshot",
    network.synthetic = true
MERGE (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
SET snapshot.processedAt = "2026-01-15T12:00:00Z",
    snapshot.importedAt = datetime(),
    snapshot.synthetic = true
MERGE (network)-[:HAS_SNAPSHOT]->(snapshot);

UNWIND [
  {id: "edge-a", displayName: "Edge A", type: "ROUTER", vendor: "Example Networks", model: "ER-1000"},
  {id: "edge-b", displayName: "Edge B", type: "ROUTER", vendor: "Example Networks", model: "ER-1000"},
  {id: "core-a", displayName: "Core A", type: "L3_SWITCH", vendor: "Example Networks", model: "CS-9000"},
  {id: "app-a", displayName: "App Leaf", type: "L3_SWITCH", vendor: "Example Networks", model: "LS-5000"},
  {id: "db-a", displayName: "Database Leaf", type: "L3_SWITCH", vendor: "Example Networks", model: "LS-5000"}
] AS item
MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
MERGE (device:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: item.id})
SET device.displayName = item.displayName,
    device.type = item.type,
    device.vendor = item.vendor,
    device.model = item.model,
    device.synthetic = true
MERGE (snapshot)-[:CONTAINS]->(device);

UNWIND [
  {leftDevice: "edge-a", leftPort: "Ethernet1", rightDevice: "core-a", rightPort: "Ethernet1"},
  {leftDevice: "edge-b", leftPort: "Ethernet1", rightDevice: "core-a", rightPort: "Ethernet2"},
  {leftDevice: "core-a", leftPort: "Ethernet3", rightDevice: "app-a", rightPort: "Ethernet1"},
  {leftDevice: "core-a", leftPort: "Ethernet4", rightDevice: "db-a", rightPort: "Ethernet1"}
] AS link
MATCH (left:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: link.leftDevice})
MATCH (right:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: link.rightDevice})
MERGE (leftInterface:ForwardInterface {
  networkId: "demo-network", snapshotId: "demo-snapshot", id: link.leftDevice + " " + link.leftPort
})
SET leftInterface.name = link.leftPort, leftInterface.deviceName = link.leftDevice,
    leftInterface.operStatus = "UP", leftInterface.synthetic = true
MERGE (rightInterface:ForwardInterface {
  networkId: "demo-network", snapshotId: "demo-snapshot", id: link.rightDevice + " " + link.rightPort
})
SET rightInterface.name = link.rightPort, rightInterface.deviceName = link.rightDevice,
    rightInterface.operStatus = "UP", rightInterface.synthetic = true
MERGE (left)-[:HAS_INTERFACE]->(leftInterface)
MERGE (right)-[:HAS_INTERFACE]->(rightInterface)
MERGE (leftInterface)-[:CONNECTED_TO]->(rightInterface);

UNWIND [
  {device: "edge-a", name: "default", routerId: "192.0.2.1", peer: "192.0.2.2", peerDevice: "edge-b", state: "ESTABLISHED"},
  {device: "edge-b", name: "default", routerId: "192.0.2.2", peer: "192.0.2.1", peerDevice: "edge-a", state: "ESTABLISHED"}
] AS item
MATCH (device:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: item.device})
MATCH (peer:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: item.peerDevice})
MERGE (instance:ForwardNetworkInstance {
  networkId: "demo-network", snapshotId: "demo-snapshot", deviceName: item.device, name: item.name
})
SET instance.instanceType = "DEFAULT_INSTANCE", instance.synthetic = true
MERGE (neighbor:ForwardBgpNeighbor {
  networkId: "demo-network", snapshotId: "demo-snapshot", deviceName: item.device,
  networkInstanceName: item.name, neighborAddress: item.peer
})
SET neighbor.localRouterId = item.routerId,
    neighbor.peerDeviceName = item.peerDevice,
    neighbor.sessionState = item.state,
    neighbor.enabled = true,
    neighbor.synthetic = true
MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
MERGE (instance)-[:HAS_BGP_NEIGHBOR]->(neighbor)
MERGE (neighbor)-[:RESOLVES_TO_DEVICE]->(peer);

UNWIND [
  {device: "app-a", interface: "Ethernet1", ip: "198.51.100.10", prefix: 24, vrf: "application"},
  {device: "db-a", interface: "Ethernet1", ip: "203.0.113.20", prefix: 24, vrf: "database"}
] AS item
MATCH (device:ForwardDevice {networkId: "demo-network", snapshotId: "demo-snapshot", id: item.device})
MATCH (interface:ForwardInterface {
  networkId: "demo-network", snapshotId: "demo-snapshot", id: item.device + " " + item.interface
})
MERGE (instance:ForwardNetworkInstance {
  networkId: "demo-network", snapshotId: "demo-snapshot", deviceName: item.device, name: item.vrf
})
SET instance.instanceType = "L3VRF", instance.synthetic = true
MERGE (address:ForwardIpAddress {
  networkId: "demo-network", snapshotId: "demo-snapshot", deviceName: item.device,
  interfaceName: item.interface, subinterfaceName: "", sourceKind: "ROUTED_VLAN",
  ip: item.ip, prefixLength: item.prefix
})
SET address.networkInstanceName = item.vrf, address.synthetic = true
MERGE (device)-[:HAS_NETWORK_INSTANCE]->(instance)
MERGE (interface)-[:HAS_IP_ADDRESS]->(address)
MERGE (address)-[:IN_NETWORK_INSTANCE]->(instance);

UNWIND ["interfaces-v1", "interface-addresses-v1", "network-instances-v1", "bgp-neighbors-v1"] AS name
MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
MERGE (run:ForwardProjectionRun {
  networkId: "demo-network", snapshotId: "demo-snapshot", name: name, version: "1", sha256: "synthetic-demo"
})
SET run.lastImportedAt = datetime(), run.synthetic = true
MERGE (snapshot)-[:HAS_PROJECTION]->(run);

MATCH (snapshot:ForwardSnapshot {networkId: "demo-network", id: "demo-snapshot"})
OPTIONAL MATCH (snapshot)-[:CONTAINS]->(device:ForwardDevice)
RETURN snapshot.id AS snapshotId, count(DISTINCT device) AS devices;
