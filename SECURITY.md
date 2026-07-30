# Security policy

## Supported versions

Security fixes are applied to the latest released version.

## Reporting

Do not open a public issue containing credentials, Forward tenant data, network or snapshot identifiers, device
configuration, Neo4j query output, logs, or screenshots from a live environment. Report suspected vulnerabilities to
Forward Networks through the company's established private security channel.

## Deployment expectations

- Keep Forward credentials in a `0600` regular file or an equivalent secret manager.
- Use HTTPS for every non-loopback Forward URL.
- Keep Neo4j authentication enabled and use a unique password.
- Leave Neo4j on loopback unless remote access is required.
- If LAN access is enabled, restrict HTTP and Bolt to the exact trusted subnet and configure TLS outside a controlled
  LAN.
- Never publish Neo4j HTTP or Bolt ports directly to the internet.
- Treat imported graph data, Neo4j backups, and logs as environment-sensitive data.

The plugin intentionally refuses HTTP redirects, arbitrary NQE source, and credential input through Cypher procedures.

## Supported use

This repository is a Forward field integration and reference implementation. It is not an officially supported
Forward product integration. The supported reference path is self-managed Neo4j on a controlled host; AuraDB and
unattended high-scale scheduling require separate deployment work.
