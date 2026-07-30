# Installation and operations

The integration runs as a Java plugin inside self-managed Neo4j. The repository service keeps Neo4j data and plugins
under the current user's home directory and listens only on loopback by default.

## Requirements

- Neo4j Community `2026.06.x`
- Java 21
- A Forward API credential with read access to the selected networks and snapshots
- Neo4j Browser or `cypher-shell` for administration

Neo4j's upstream references cover [Linux installation](https://neo4j.com/docs/operations-manual/current/installation/linux/tarball/),
[plugin installation](https://neo4j.com/docs/operations-manual/current/configuration/plugins/), and
[connector configuration](https://neo4j.com/docs/operations-manual/current/configuration/connectors/).

## Arch Linux

Install the AUR package requested for this reference setup:

```bash
paru -S neo4j-community
neo4j --version
java --version
```

Build from source, or download `forward-neo4j-plugin-0.2.0.jar` from the GitHub release:

```bash
./gradlew clean test jar
```

Install the plugin and user service:

```bash
install -d -m 0700 ~/.local/state/forward-neo4j/plugins ~/.config/forward-neo4j
install -d ~/.config/systemd/user
install -m 0644 build/libs/forward-neo4j-plugin-0.2.0.jar \
  ~/.local/state/forward-neo4j/plugins/forward-neo4j-plugin.jar
install -m 0644 deploy/neo4j.conf ~/.config/forward-neo4j/neo4j.conf
install -m 0644 deploy/forward-neo4j.service ~/.config/systemd/user/forward-neo4j.service
install -m 0600 /dev/null ~/.config/forward-neo4j/forward.credentials
```

## Forward credentials

Edit `~/.config/forward-neo4j/forward.credentials` and add three required lines plus an optional fourth:

```text
API client ID or username
API client secret or password
Forward HTTPS hostname or base URL
optional default network ID
```

Choose the installed tier in the user service. The shipped default is the conservative Base tier:

```bash
systemctl --user edit forward-neo4j.service
```

```ini
[Service]
Environment=FORWARD_LICENSE_TIER=NS
```

Use the organization's actual tier (`B`, `N`, `NP`, `S`, `NS`, or `NSP`). `NS` and `NSP` enable every current
integration feature.

The plugin rejects a credential file that is not a regular file or has any group/other permission bits. Keep it out of
the repository and confirm mode `0600`:

```bash
chmod 0600 ~/.config/forward-neo4j/forward.credentials
stat -c '%a %n' ~/.config/forward-neo4j/forward.credentials
```

Environment variables are also supported. Explicit environment values take precedence over file values.

| Variable | Purpose | Default |
|---|---|---|
| `FORWARD_CREDENTIAL_FILE` | Protected three- or four-line credential file | unset |
| `FORWARD_URL` | Forward HTTPS base URL | credential line 3 |
| `FORWARD_USERNAME` | Basic-auth username or API client ID | credential line 1 |
| `FORWARD_PASSWORD` | Basic-auth password or API client secret | credential line 2 |
| `FORWARD_TOKEN` | Standalone token used as Basic username with an empty password | unset |
| `FORWARD_DEFAULT_NETWORK_ID` | Default network | credential line 4 |
| `FORWARD_LICENSE_TIER` | Local feature selector: `B`, `N`, `NP`, `S`, `NS`, `NSP` | `B` |
| `FORWARD_HTTP_TIMEOUT_SECONDS` | Per-request timeout | `60` |
| `FORWARD_NQE_POLL_SECONDS` | Async execution poll interval, minimum 5 | `5` |
| `FORWARD_NQE_TIMEOUT_SECONDS` | Total async execution wait | `1800` |
| `FORWARD_NQE_PAGE_SIZE` | Materialized result page size, maximum 10,000 | `5000` |

## First start

Neo4j authentication is enabled by default. Before the first start, set a unique initial password if your package has
not already initialized the database:

```bash
NEO4J_HOME="$HOME/.local/state/forward-neo4j" \
  NEO4J_CONF="$HOME/.config/forward-neo4j" \
  neo4j-admin dbms set-initial-password
```

Start the service and enable it only if start-at-login is wanted:

```bash
systemctl --user daemon-reload
systemctl --user start forward-neo4j.service
systemctl --user enable forward-neo4j.service
systemctl --user status forward-neo4j.service
```

Use `cypher-shell --change-password` for an existing account that needs rotation.

## Validate the real integration

An installed/enabled service is not proof that Forward access works. Run the procedures against Bolt:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  'CALL forward.plugin.info() YIELD version RETURN version'

cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  'CALL forward.testConnection() YIELD authenticated, accessibleNetworks,
   defaultNetworkConfigured, defaultNetworkVisible, durationMillis RETURN *'
```

The connection test performs a real Forward `GET /api/networks` call but emits no network names or IDs.

Confirm the selected feature surface before importing:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  'CALL forward.license.features() YIELD feature, enabled, requiredFacet RETURN * ORDER BY feature'
```

See [license profiles](LICENSE_PROFILES.md). The selector is a local guardrail and does not grant Forward entitlement.

Confirm the selected feature surface before importing:

```bash
cypher-shell -a bolt://127.0.0.1:7687 -u neo4j \
  'CALL forward.license.features() YIELD feature, enabled, requiredFacet RETURN * ORDER BY feature'
```

See [license profiles](LICENSE_PROFILES.md). The selector is a local guardrail and does not grant Forward entitlement.

## Trusted-LAN access

Keep the default loopback binding unless another workstation needs access. To expose Neo4j on a trusted IPv4 LAN,
bind only HTTP and Bolt, set an address clients can reach, and constrain both ports at the host firewall. Do not set
`server.default_listen_address=0.0.0.0`; that global setting can expose additional Neo4j connectors.

First identify the LAN address and subnet. The examples below use `192.168.1.50` and `192.168.1.0/24`; replace both:

```bash
ip -4 route get 1.1.1.1
ip -4 address show
```

Edit `~/.config/forward-neo4j/neo4j.conf` and replace the four loopback connector lines with:

```properties
server.http.listen_address=0.0.0.0:7474
server.http.advertised_address=192.168.1.50:7474
server.http.x_forward.enabled=false
server.bolt.listen_address=0.0.0.0:7687
server.bolt.advertised_address=192.168.1.50:7687
server.fleet_discovery.enabled=false
```

The reference service is not behind a reverse proxy, so it ignores `X-Forwarded-*` headers and disables standalone
Fleet discovery broadcasts. If a trusted reverse proxy is added later, enable forwarded-header processing only with
explicit `server.http.x_forward.allow_proxies` and `server.http.x_forward.allow_hosts` values.

For UFW, permit only the local subnet:

```bash
sudo ufw allow proto tcp from 192.168.1.0/24 to any port 7474 comment 'Neo4j Browser LAN'
sudo ufw allow proto tcp from 192.168.1.0/24 to any port 7687 comment 'Neo4j Bolt LAN'
sudo ufw status numbered
systemctl --user daemon-reload
systemctl --user restart forward-neo4j.service
```

Verify listeners, HTTP, Bolt, and the plugin—not merely the process:

```bash
ss -ltn '( sport = :7474 or sport = :7687 )'
curl --fail http://192.168.1.50:7474/
cypher-shell -a bolt://192.168.1.50:7687 -u neo4j \
  'CALL forward.plugin.info() YIELD version RETURN version'
```

Open `http://192.168.1.50:7474` from the LAN workstation and connect to `bolt://192.168.1.50:7687`.

HTTP and Bolt are unencrypted in this example. Use it only on a trusted, controlled LAN. For any less-trusted network,
configure Neo4j's [SSL policies for HTTPS and Bolt TLS](https://neo4j.com/docs/operations-manual/current/security/ssl-framework/)
and restrict the firewall further. Never forward ports 7474 or 7687 from an internet router.

## Docker demo

```bash
docker compose -f demo/compose.yaml up --build
```

The container publishes 7474 and 7687. Docker port publishing may interact with host firewall rules differently from a
native service; bind it to loopback when remote access is not required:

```yaml
ports:
  - "127.0.0.1:7474:7474"
  - "127.0.0.1:7687:7687"
```

Do not mount a workstation credential file unless the exposure is intentional and the file remains protected.

## Upgrade and rollback

Stop the service only for the short JAR replacement window:

```bash
systemctl --user stop forward-neo4j.service
install -m 0644 forward-neo4j-plugin-NEW.jar \
  ~/.local/state/forward-neo4j/plugins/forward-neo4j-plugin.jar
systemctl --user start forward-neo4j.service
```

Retain the previous release JAR until `forward.plugin.info()`, `forward.testConnection()`, and a representative query
all succeed. Rolling back is the same operation with the previous JAR. A plugin rollback does not automatically revert
graph schema or imported data.
