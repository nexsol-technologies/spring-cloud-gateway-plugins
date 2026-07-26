# spring-cloud-gateway-routes

Route definition management for Spring Cloud Gateway with pluggable sources. Every source
produces standard `RouteDefinition`s that are aggregated into the gateway route locator, so
the sources can be combined freely.

## Modules

| Module | Source | Access |
| --- | --- | --- |
| [spring-cloud-gateway-routes-core](spring-cloud-gateway-routes-core) | Shared caching/refresh locator infrastructure | — |
| [spring-cloud-gateway-routes-database](spring-cloud-gateway-routes-database/README.md) | Database (R2DBC) with a light GUI | read/write (CRUD + UI) |
| [spring-cloud-gateway-routes-files](spring-cloud-gateway-routes-files/README.md) | JSON/YAML files (GitOps / CI pipelines) | read-only |
| [spring-cloud-gateway-routes-configserver](spring-cloud-gateway-routes-configserver/README.md) | JSON/YAML files served by Spring Cloud Config Server | read-only |
| [spring-cloud-gateway-routes-openapi](spring-cloud-gateway-routes-openapi/README.md) | OpenAPI contract | read-only |
| [spring-cloud-gateway-routes-security](spring-cloud-gateway-routes-security/README.md) | Route metadata (`public`) → Spring Security | cross-cutting |

Routes flagged public (via `metadata.public: true`, whatever the source) are exempted from
Spring Security (Basic auth, OAuth 2.0) by the `spring-cloud-gateway-routes-security` module.

> Note: this is different from `spring-cloud-gateway-hub-openapi`, which aggregates the OpenAPI
> documentation of downstream services (a documentation hub). This module's `-openapi`
> sub-module instead **generates gateway routes** from an OpenAPI contract.

## Usage

Add the sub-module matching your source. Each is independently activated by its own
`enabled` property and can be mixed with the others.

```xml
<dependency>
    <groupId>ch.nexsol.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-files</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Refreshing routes

Every source whose locator extends `AbstractRefreshableRouteDefinitionLocator` (the `files`,
`configserver` and `openapi` sources) caches its route definitions and reloads them:

- at startup;
- on the source's own trigger (file watch, poll interval, …);
- on **`/actuator/refresh`** and **`/actuator/busrefresh`** (Spring Cloud Bus).

The `/refresh` support is provided once, in `spring-cloud-gateway-routes-core`: a shared listener
reloads **all** refreshable locators on `RefreshScopeRefreshedEvent`, re-reading each source from
scratch (not just rebuilding the gateway from the cached snapshot). It is wired only when the
Spring Cloud Config client (`spring-cloud-context`) is on the classpath — i.e. whenever the gateway
is itself a Config Server client — and degrades gracefully otherwise. Expose the endpoints as usual:

```yaml
management.endpoints.web.exposure.include: refresh, busrefresh
```

> The `database` source is not cached (it reads on demand), so it always reflects the latest
> state without needing this mechanism.
