# spring-cloud-gateway-hub-openapi

This plugin aggregates the OpenAPI documentation of downstream services into a hub for Spring Cloud Gateway.

> To generate gateway routes from an OpenAPI contract instead, use
> [spring-cloud-gateway-routes-openapi](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md).

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol-tech.gateway</groupId>
           <artifactId>spring-cloud-gateway-hub-openapi</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## Using Discovery client

If Spring Cloud Gateway use route locator with discovery client (like eureka), this plugin search for openapi documentation in down stream client (with default path `/v3/api-docs`).

When the application has no discovery client, the discovery-based beans simply back off:
the hub keeps aggregating the statically configured contracts described below.

```yaml
spring.cloud.gateway.server.webflux:
  discovery:
    locator:
      enabled: true
```

## Aggregating statically configured OpenAPI contracts

When [spring-cloud-gateway-routes-openapi](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md)
is also on the classpath, the OpenAPI contracts it is configured with are automatically
exposed in the aggregated Swagger UI, side by side with the discovered services. This
covers the sources declared inline **and** those read from a document through
`sources-locations` — the hub resolves the sources the same way the generator does, so a
contract declared in a document served by a Config Server appears in the dropdown too.

A source carrying a `path-prefix` has its contract advertised with that prefix, so
"Try it out" calls the prefixed route the generator created rather than the bare contract
path. Each
source's contract is proxied through the gateway (its `servers` section rewritten to the
gateway), so there is no CORS issue and "Try it out" targets the gateway.

No extra configuration is needed beyond enabling both plugins:

```yaml
spring.cloud.gateway.server.webflux.hub-openapi:
  enabled: true                               # enable the hub / Swagger UI aggregation
  gateway-uri: http://localhost:8181          # required by the hub to rewrite the servers
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources:
    - id: petstore
      uri: https://petstore3.swagger.io
      spec-url: https://petstore3.swagger.io/api/v3/openapi.json
      mode: PER_OPERATION
```

The source then appears in the Swagger UI dropdown as `petstore`, served through the
gateway at `/v3/api-docs/petstore`.

## Spring Security

When Spring Security is on the classpath and the hub is enabled, the plugin contributes its
own `SecurityWebFilterChain` so the Swagger UI and the aggregated contracts stay reachable.
Nothing has to be declared.

What is permitted:

| Path | What it serves |
|---|---|
| `springdoc.api-docs.path` (default `/v3/api-docs`), and its `.yaml` and `/swagger-config` variants | the contract of the gateway itself and the Swagger UI configuration |
| `/v3/api-docs/*` | the aggregated contracts, published as gateway routes named after each service |
| `springdoc.swagger-ui.path` (default `/swagger-ui.html`), `/swagger-ui/**`, `/webjars/swagger-ui/**` | the Swagger UI page and its assets |

The SpringDoc paths are read from the SpringDoc configuration, so a custom
`springdoc.api-docs.path` or `springdoc.swagger-ui.path` is honoured. The aggregated
contracts are matched **one segment deep** (`/v3/api-docs/*`), and the assets are scoped to
the Swagger UI webjar, so no unrelated gateway route inherits the documentation
permissions &mdash; `/webjars/**` as a whole is never opened.

The chain is ordered at `HubApiSecurityAutoConfiguration.HUB_OPENAPI_CHAIN_ORDER`
(`Ordered.HIGHEST_PRECEDENCE + 400`), ahead of the chains an application usually declares
from `@Order(1)`. Two escape hatches: declare your own bean named
`hubOpenapiSecurityWebFilterChain` (the plugin backs off), or turn it off:

```yaml
spring.cloud.gateway.server.webflux.hub-openapi:
  security-chain-enabled: false
```

> As with any `SecurityWebFilterChain` bean, its presence makes Spring Boot back off from
> its default "everything authenticated" chain. An application that was relying on that
> default must declare its own chains.
