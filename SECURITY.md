# Security policy

## Supported versions

Fixes go to the line marked Current in [Compatibility](README.md#compatibility) — `1.17.x`
today — and to every line that table marks Maintained. A line goes to end of life as soon as a
new Current line opens on the same Spring Boot version, since moving to it needs no Spring Boot
upgrade; a line on an older Spring Boot version keeps its own branch.

## Reporting a vulnerability

Do not open a public issue for a security problem. Report it privately through GitHub's
[private vulnerability reporting](https://github.com/nexsol-technologies/spring-cloud-gateway-plugins/security/advisories/new),
or by email to info@nexsol.tech if you cannot use GitHub.

Include the affected module and version, the configuration the gateway runs under, and the
steps to reproduce. You will get an acknowledgement within five business days and an
assessment — accepted, or declined with the reason — within fifteen.

An accepted report is fixed on every supported line, released, and published as a GitHub
security advisory crediting the reporter unless they ask otherwise.
