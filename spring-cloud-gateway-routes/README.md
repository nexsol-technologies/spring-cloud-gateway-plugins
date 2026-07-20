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
