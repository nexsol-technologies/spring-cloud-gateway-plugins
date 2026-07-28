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

A service can declare where its document is, in its instance metadata, instead of letting
the hub probe the well-known SpringDoc paths. This is one HTTP call per service instead of
up to three, and it is worth doing on a large registry:

```yaml
eureka.instance.metadata-map.openapi_path: /v3/api-docs
```

### Sizing the discovery

The gateway refreshes its routes on every discovery heartbeat, and each refresh probes the
services it does not know about yet. The defaults below keep that cost constant whatever
the size of the registry: on a registry holding hundreds of services, probing them all at
once saturates the connection pool, and the probes then fail with
`PoolAcquirePendingLimitException` or `PoolAcquireTimeoutException` while the gateway stops
routing.

```yaml
spring.cloud.gateway.server.webflux.hub-openapi:
  enabled: true
  gateway-uri: http://localhost:8181
  discovery:
    timeout: 2s              # gives up on a service that does not answer
    concurrency: 16          # services probed at the same time
    max-connections: 50      # size of the pool dedicated to the probes
    cache-ttl: 5m            # how long a probe result is remembered
```

| Setting | What it does |
|---|---|
| `timeout` | Bounds a single probe, connection included. Without it a service that accepts connections but never answers holds the whole route refresh, and the connection it uses. |
| `concurrency` | Number of services probed at the same time, whatever the number of discovered services. |
| `max-connections` | The probes use a connection pool of their own, so they never compete for the connections the gateway proxies its traffic on. |
| `cache-ttl` | The path a document was found at &mdash; or the confirmed absence of a document &mdash; is remembered per service instance, so the next heartbeat does not probe the whole registry again. Set to `0` to probe on every refresh. |

Only a service that answered has its result cached. A service that could not be reached is
probed again on the next refresh, so a service that was down when the gateway started
appears in the hub as soon as it comes back, without waiting for `cache-ttl`.

The documents themselves are never buffered by the discovery: only the path each document
was found at is kept, and the response body is released. The documents are fetched, and
their `servers` section rewritten, when the Swagger UI actually asks for them.

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
