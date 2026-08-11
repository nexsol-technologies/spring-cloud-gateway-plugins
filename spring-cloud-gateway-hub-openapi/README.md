# spring-cloud-gateway-hub-openapi

Aggregates the OpenAPI documentation of the downstream services into a single hub, served by
the gateway.

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

## Discovering the contracts through the discovery client

When the gateway routes through a discovery client (Eureka, for instance), the hub fetches the
contract of every discovered service, at `/v3/api-docs` by default.

An application without a discovery client makes the discovery-based beans back off; the hub then
serves the statically configured contracts described below.

```yaml
spring.cloud.gateway.server.webflux:
  discovery:
    locator:
      enabled: true
```

A service can declare where its document is, in its instance metadata, instead of letting
the hub probe the well-known SpringDoc paths. This is one HTTP call per service instead of
up to three, which matters on a large registry:

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

Probing an instance stops at the first path it could not be reached on, instead of trying
the remaining ones: they lead to the same instance and fail the same way. An unreachable
service therefore costs one `timeout`, not one per candidate path &mdash; which is what
keeps the few services that are always down or draining in a large registry from dominating
the refresh.

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
"Try it out" calls the prefixed route the generator created rather than the bare contract path.
Each source's contract is proxied through the gateway, its `servers` section rewritten to the
gateway, so "Try it out" targets the gateway and raises no CORS issue.

Enabling both plugins is all that is required:

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

## Which issuer the documents advertise

A service names, in its `openIdConnect` security scheme, the issuer **it** validates its own
traffic against &mdash; routinely an address internal to the cluster. The document is read in
a browser, where that address resolves to nothing: the console shows a discovery URL nobody
can reach, and no token can be obtained to try an operation with.

The gateway knows better. It is configured with the issuers it accepts on the traffic it
routes, and a token good enough for the traffic is exactly the token an operation needs:

```yaml
spring.cloud.gateway.server.webflux.hub-openapi:
  security:
    issuer: gateway            # DOCUMENT (default) | GATEWAY
```

| Value | What the documents say |
|---|---|
| `DOCUMENT` | Whatever their service wrote, untouched. The default, and the behaviour the plugin has always had. |
| `GATEWAY` | The issuers of this gateway, read from `spring.security.oauth2.resourceserver` &mdash; nothing to declare twice. |

Both shapes are read, and neither has to be the one the console itself signs in through:

```yaml
spring.security.oauth2.resourceserver:
  multitenant:                 # each tenant contributes an issuer, under its id
    - id: local
      issuer-uri: http://localhost:9090
    - id: partner
      issuer-uri: https://partner.example.ch/realms/care
  # or, for a single issuer:
  jwt:
    issuer-uri: http://localhost:9090
```

With **one** issuer, every `openIdConnect` scheme keeps its name and only its discovery URL
changes. With **several**, each scheme becomes one scheme per tenant &mdash; `bearer-oidc`
becomes `bearer-oidc-local` and `bearer-oidc-partner` &mdash; and every requirement naming it,
at the root of the document and on each operation carrying its own, becomes one alternative
per tenant. An OpenAPI `security` list is a disjunction, so the console offers the tenants as
the choice they are rather than one of them as a fact.

Only the discovery URL moves. The schemes, the scopes and the operations are the service's
own, and a scheme that is not `openIdConnect` is left alone. Asking for `GATEWAY` on a gateway
configured with no issuer changes nothing and says so at start-up.

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

## Auditing

When the [auditing plugin](../spring-cloud-gateway-audit/spring-cloud-gateway-audit-core/README.md)
is on the classpath, the hub keeps its documentation endpoints out of the audit trail: a
console polling the contracts calls them over and over, and that traffic says nothing about
what the gateway routed. The dependency is optional, so auditing is never forced on a
gateway that only wants the hub.

The excluded paths are the very ones listed above &mdash; the security chain and the audit
exclusion read the same resolved list, so a path opened by one is never audited by the
other. That includes `/v3/api-docs/*`, which are **real proxied routes** to the backends:
excluding them takes genuinely routed traffic out of the trail. If you would rather keep it,
set the exclusions yourself with
`spring.cloud.gateway.server.webflux.audit.web-filter.exclude-paths` &mdash; the list is
additive, so what you configure is kept and only the missing hub paths are appended.

The exclusion only applies to the global audit web filter. A route carrying the `Audit`
gateway filter is audited whatever this says.
