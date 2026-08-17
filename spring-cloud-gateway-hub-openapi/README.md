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

Only a service that answered **"not there"** has its result cached. A service that could not
be reached, or that refused to hand its document over, has said nothing about having one:
it is probed again on the next refresh, so a service that was down when the gateway started
appears in the hub as soon as it comes back, without waiting for `cache-ttl`.

Probing an instance stops as soon as the instance turns out to be unreachable, instead of
trying the remaining paths: they lead to the same instance and fail the same way. An
unreachable service therefore costs one `timeout`, not one per candidate path &mdash; which
is what keeps the few services that are always down or draining in a large registry from
dominating the refresh.

A refusal does not stop it. Authorization is granted path by path: a rule permitting
`/v3/api-docs/**` serves `/v3/api-docs` and answers `401` on `/v3/api-docs.json` and
`/v3/api-docs.yaml`, which that pattern does not match. The remaining paths are therefore
probed &mdash; at the cost of an immediate answer each, not a timeout &mdash; and the service
is only reported as refusing its document once none of them served it.

### A service that does not appear in the hub

A discovered service missing from the dropdown is either a service without a document, which
is normal and silent, or a service the hub could not read one from, which is a configuration
to fix and says so:

```
WARN  Route ALERT-SERVICE is left out of the OpenAPI hub: http://10.1.2.3:8080/v3/api-docs.json
      answered 401 UNAUTHORIZED. Its document is protected, and the hub reads it anonymously.
```

The same reason is logged once per `cache-ttl` rather than on every heartbeat, and again as
soon as it changes. Turn `ch.nexsol.gateway.openapi.hub` to `debug` for the outcome of every
single probe, path by path.

The probes are anonymous, and there is no way to give them a credential: a service whose
contract is worth aggregating serves it to the gateway. `401` is therefore reported rather
than worked around &mdash; and note that it says nothing about the **service**, which is
routed as usual: only its contract is missing from the hub.

A service missing from the dropdown without a word at all is a service the hub never
considered, which is a different matter &mdash; see
[below](#a-service-routed-without-the-discovery-client).

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

### A service routed without the discovery client

The hub builds its documentation routes from the routes the **discovery locator** produced,
and from those alone. A service routed any other way — declared by hand in
`spring.cloud.gateway.server.webflux.routes`, or coming from `routes-files`,
`routes-configserver`, `routes-database` — is never probed and never appears in the dropdown.
Nothing is logged, because nothing was attempted.

Declaring it as an OpenAPI source is what puts it back, and `mode: NO_ROUTE` keeps the
generator from adding a second set of routes in front of a backend that already has some:

```yaml
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources:
    - id: alert-service
      spec-url: https://alert-service.internal/v3/api-docs
      mode: NO_ROUTE                  # the contract only; the routes are declared elsewhere
      path-prefix: /ALERT-SERVICE     # the prefix those routes answer under
```

The contract is proxied and its `servers` rewritten exactly like any other source, so "Try it
out" targets `path-prefix` on the gateway — which has to be the prefix the existing routes
answer under, or the console calls paths nothing serves.

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

### When the console is there

A gateway that also carries the [UI console](../spring-cloud-gateway-ui/README.md) usually
wants one answer to "who may read this", not two. The hub therefore declares the same paths
to the console, which governs them with a chain ordered ahead of this one:

| Path | Under the console |
|---|---|
| The Swagger UI, its assets, `/swagger-config` and the aggregated contracts | Follow the console: open while it is open, behind its login once it is not |
| `springdoc.api-docs.path` and its `.json` / `.yaml` variants | Stay open |

The contract of the gateway itself stays open because the hub reads it over HTTP, on every
registered instance and its own included, with a client that carries no credentials.
Closing it would not protect anything: it would remove the gateway from its own hub. Giving
that poll a way to authenticate is the next step, and until then this is the honest
description of what is exposed &mdash; the shape of the API, not its data.

The declaration is a plain bean, so the same escape hatch applies: declare your own
`hubOpenapiSecuredPaths` and the plugin backs off.

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
