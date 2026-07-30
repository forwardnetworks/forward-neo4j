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

### Native Neo4j Browser module

Neo4j Community 2026 uses a Browser zip in [`NEO4J_HOME/web`](https://neo4j.com/docs/operations-manual/current/configuration/file-locations/#file-locations-web).
Some source-built distribution packages, including the Arch `neo4j-community` `2026.06.0-1` package, install the
database JARs but omit that web archive. The database API at port 7474 still works in that state, but `/browser/`
returns `404` and there is no graph UI.

This repository provides a checksum-pinned installer for the
[official Neo4j Browser release](https://github.com/neo4j/neo4j-browser/releases). It packages the release in the zip
layout expected by Neo4j's native `BROWSER` HTTP module; it does not start a second web server:

```bash
./deploy/install-neo4j-browser.sh "$HOME/.local/state/forward-neo4j"
systemctl --user restart forward-neo4j.service
curl --fail --header 'Accept: text/html' http://127.0.0.1:7474/browser/ >/dev/null
```

For the package's system service, install into its native home instead and restart the system unit:

```bash
sudo ./deploy/install-neo4j-browser.sh /var/lib/neo4j
sudo systemctl restart neo4j.service
curl --fail --header 'Accept: text/html' http://127.0.0.1:7474/browser/ >/dev/null
```

The installer refuses broad target paths, verifies the downloaded release SHA-256, checks the expected Browser entry
point, and writes only the versioned archive under the selected `NEO4J_HOME/web` directory. Future package upgrades
should be rechecked because an updated Arch recipe may begin shipping the upstream web archive directly.

Build from source, or download `forward-neo4j-plugin-0.2.1.jar` from the GitHub release:

```bash
./gradlew clean test jar
```

Install the plugin and user service:

```bash
install -d -m 0700 ~/.local/state/forward-neo4j/plugins ~/.config/forward-neo4j
install -d ~/.config/systemd/user
deploy/install-plugin.sh build/libs/forward-neo4j-plugin-0.2.1.jar \
  ~/.local/state/forward-neo4j
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
read -rsp 'New Neo4j password: ' neo4j_initial_password
printf '\n'
NEO4J_HOME="$HOME/.local/state/forward-neo4j" \
  NEO4J_CONF="$HOME/.config/forward-neo4j" \
  neo4j-admin dbms set-initial-password "$neo4j_initial_password"
unset neo4j_initial_password
```

The built-in administrator username is `neo4j`, not `admin`. If an initial password is not set explicitly, Neo4j
defaults to `neo4j` and requires a change on first login. Do not expose the connectors on the LAN while that default is
usable.

Start the service and enable it only if start-at-login is wanted:

```bash
systemctl --user daemon-reload
systemctl --user start forward-neo4j.service
systemctl --user enable forward-neo4j.service
systemctl --user status forward-neo4j.service
```

Use `cypher-shell --change-password` for an existing account that needs rotation.

`neo4j-admin dbms set-initial-password` only works before the database is first started. If the password for an
initialized database is lost, follow Neo4j's
[admin password recovery procedure](https://neo4j.com/docs/operations-manual/current/authentication-authorization/password-and-user-recovery/):
stop Neo4j, disable authentication only while every enabled connector is bound to `127.0.0.1`, set the password in the
`system` database, then stop Neo4j again and restore authentication and the original connector bindings before
restarting. Never leave an authentication-disabled instance reachable from the LAN.

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
read -rsp 'Neo4j demo password: ' NEO4J_PASSWORD
export NEO4J_PASSWORD
docker compose -f demo/compose.yaml up --build
```

The Compose file requires an explicit Neo4j password and binds ports 7474 and 7687 to loopback. Deliberately change
those bindings only when a remote workstation needs access, then apply the same subnet and TLS controls as the native
service. Docker port publishing may interact with host firewall rules differently from a native service.

Do not mount a workstation credential file unless the exposure is intentional and the file remains protected.

## Upgrade and rollback

Stop the service only for the short JAR replacement window:

```bash
systemctl --user stop forward-neo4j.service
deploy/install-plugin.sh forward-neo4j-plugin-NEW.jar \
  ~/.local/state/forward-neo4j
systemctl --user start forward-neo4j.service

read -rsp 'Neo4j password: ' NEO4J_PASSWORD
export NEO4J_PASSWORD
deploy/verify-install.sh --forward
unset NEO4J_PASSWORD
```

The installer validates an adjacent `.sha256` file or `SHA256SUMS` manifest when present, checks the procedure class and
all four bundled NQEs, uses an atomic rename, and retains the previous plugin as
`forward-neo4j-plugin.jar.previous`. The non-`.jar` suffix prevents Neo4j from loading the rollback copy alongside the
active plugin. Keep that rollback copy until `verify-install.sh --forward` and a representative query succeed. A plugin
rollback does not automatically revert graph schema or imported data.
