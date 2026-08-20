# spring-cloud-gateway-routes

Route definition management with pluggable sources. Every source produces standard
`RouteDefinition`s aggregated into the gateway route locator, so the sources combine freely
with each other and with the routes declared in `application.yml`.

## Modules

| Module | Source | Access |
| --- | --- | --- |
| `-core` | Shared caching and refresh infrastructure | — |
| [`-database`](spring-cloud-gateway-routes-database/README.md) | A database (R2DBC), with a console view over it | read/write |
| [`-files`](spring-cloud-gateway-routes-files/README.md) | JSON/YAML files (GitOps, CI pipelines) | read-only |
| [`-configserver`](spring-cloud-gateway-routes-configserver/README.md) | JSON/YAML files served by a Spring Cloud Config Server | read-only |
| [`-openapi`](spring-cloud-gateway-routes-openapi/README.md) | An OpenAPI contract | read-only |
| [`-security`](spring-cloud-gateway-routes-security/README.md) | Route metadata (`public`) → Spring Security | cross-cutting |

> Not to be confused with
> [spring-cloud-gateway-hub-openapi](../spring-cloud-gateway-hub-openapi/README.md), which
> aggregates the OpenAPI *documentation* of downstream services. The `-openapi` module here
> **generates routes** from a contract.

## Install

Add the sub-module matching your source — each is activated by its own `enabled` property and
mixes with the others.

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-files</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Public routes

Whatever the source, a route whose metadata carries `public: true` is exempted from Spring
Security by [`-security`](spring-cloud-gateway-routes-security/README.md).

## Refreshing routes

The `files`, `configserver` and `openapi` sources cache their route definitions and reload
them:

* at startup;
* on the source's own trigger (file watch, poll interval, …);
* on `/actuator/refresh` and `/actuator/busrefresh` (Spring Cloud Bus).

```yaml
management.endpoints.web.exposure.include: refresh, busrefresh
```

The `/refresh` support is provided once, in `-core`: a shared listener reloads **all**
refreshable locators on `RefreshScopeRefreshedEvent`, re-reading each source from scratch
rather than rebuilding the gateway from a cached snapshot. It is wired only when
`spring-cloud-context` is on the classpath — i.e. whenever the gateway is itself a Config
Server client — and degrades gracefully otherwise.

> The `database` source is not cached: it reads on demand and always reflects the latest
> state, so it needs none of this.

## Sample

[gateway-routes-all](../spring-cloud-gateway-samples/gateway/gateway-routes-all/README.md) —
port `8210`, every source at once.
