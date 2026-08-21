<p align="center">
  <img src="logo2.png" alt="spring-cloud-gateway-plugins-logo" width="90%"/>
  <br>
  <em>Plugins for Spring Cloud Gateway.</em>
  <br>
</p>
<hr>

# Spring Cloud Gateway plugins by [neXsol technologies](https://nexsol.tech)

Drop-in plugins for a Spring Cloud Gateway (WebFlux) application: route sources, security
filters, auditing, observability, and a web console that lights up a view per plugin present on
the classpath. Each one is activated by configuration alone.

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-ui</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Plugins

| Plugin | What it does |
| --- | --- |
| [ui](spring-cloud-gateway-ui/README.md) | A Spring Boot Admin-like console under `/ui`: the resolved routes and the source each came from, a route tester, a traffic chart, the health of every instance, a service graph and a live audit tail. Also where the HTTP endpoints of the other plugins are governed |
| [routes](spring-cloud-gateway-routes/README.md) | Route definitions from pluggable sources aggregated into one locator: a database (with a management view), JSON/YAML files, a Config Server, OpenAPI contracts |
| [filters](spring-cloud-gateway-filters/README.md) | `Authorization`, `ConvertHttpMethod`, `CorrelationId`, `IdentityPropagation` and `Recaptcha` |
| [oauth2](spring-cloud-gateway-oauth2/README.md) | Multi-tenant authentication, JWT validation, Basic-to-Bearer exchange, and the `AuthorizationToken` filter validating an access token per route |
| [audit](spring-cloud-gateway-audit/README.md) | Audits requests and responses — JWT, request, response, trace and route attributes, toggled by group — and pushes them to a pluggable provider (Redis, Kafka, database) |
| [metrics](spring-cloud-gateway-metrics/README.md) | The figures the console plots: traffic per route, health per instance. Local by default, consolidated across instances through Prometheus, Redis or the service registry |
| [service-graph](spring-cloud-gateway-service-graph/README.md) | Who calls what: one counter per routed exchange, from the client a token was issued to towards the service a route targets. Read back locally, from Redis, from Prometheus, or from the graph Tempo derives from spans |
| [openapi-validation](spring-cloud-gateway-openapi-validation/README.md) | Holds the requests and responses of a route against an OpenAPI contract, each direction enforced or reported on separately, every outcome counted and audited |
| [hub-openapi](spring-cloud-gateway-hub-openapi/README.md) | Aggregates the OpenAPI documentation of the downstream services into a single Swagger UI |
| [commons](spring-cloud-gateway-commons/README.md) | The contracts the plugins share: how a plugin declares the paths it serves, and how the running instance is named |

## Samples

[spring-cloud-gateway-samples](spring-cloud-gateway-samples/README.md) — one runnable gateway
per plugin, plus four exercising them in combination. Start with
[gateway-full](spring-cloud-gateway-samples/gateway/gateway-full/README.md) to see everything
at once.

## Tools

[tools](tools/README.md) — scripts for the maintenance of this repository, such as re-capturing
the screenshots of the console the READMEs embed.

## Compatibility

Java 21 and a Spring Boot (WebFlux) application. A release line is fixed to one Spring Boot
line and is never moved to another one:

| Line | Spring Boot | Spring Cloud | Status |
| --- | --- | --- | --- |
| `1.14.x` | 4.0.Y | 2025.1.x | Current, developed on `main` |
| `1.5.x` – `1.13.x` | 4.0.Y | 2025.1.x | End of life |
| `1.0.x` – `1.3.x` | 3.5.Y | 2025.0.x | End of life |

## Reference documentation

[Spring Cloud Gateway reference documentation](https://docs.spring.io/spring-cloud-gateway/reference/)

## Getting help

Report bugs and ask questions at
https://github.com/nexsol-technologies/spring-cloud-gateway-plugins/issues

## Trademarks and licenses

The source code of neXsol's Spring Cloud Gateway plugins is licensed under the
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Spring, Spring Boot and Spring Cloud are trademarks of
[Broadcom Inc.](https://www.broadcom.com/) and/or its subsidiaries in the U.S. and other
countries.
