# spring-cloud-gateway-hub-openapi

Aggregates the OpenAPI documentation of the downstream services into a single hub — one
Swagger UI, served by the gateway, with a dropdown per service.

> To **generate gateway routes** from an OpenAPI contract instead, use
> [routes-openapi](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md).

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-hub-openapi</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.hub-openapi`.

```yaml
spring.cloud.gateway.server.webflux.hub-openapi:
  enabled: true
  # Required: the address the contracts are rewritten to advertise.
  gateway-uri: http://localhost:8181
  discovery:
    timeout: 2s              # gives up on a service that does not answer
    concurrency: 16          # services probed at the same time
    max-connections: 50      # size of the pool dedicated to the probes
    cache-ttl: 5m            # how long a probe result is remembered
  security:
    issuer: GATEWAY          # DOCUMENT (default) | GATEWAY
  security-chain-enabled: true
```

| Property | Default | What it does |
| --- | --- | --- |
| `...hub-openapi.enabled` | `false` | Activates the hub |
| `...hub-openapi.gateway-uri` | — | Address the `servers` section of every contract is rewritten to; required |
| `...hub-openapi.discovery.timeout` | `2s` | Bounds a single probe, connection included |
| `...hub-openapi.discovery.concurrency` | `16` | Services probed at the same time, whatever the size of the registry |
| `...hub-openapi.discovery.max-connections` | `50` | Connection pool dedicated to the probes, so they never compete with routed traffic |
| `...hub-openapi.discovery.cache-ttl` | `5m` | How long a probe result is remembered; `0` probes on every refresh |
| `...hub-openapi.security.issuer` | `DOCUMENT` | Which issuer the documents advertise: `DOCUMENT` or `GATEWAY` |
| `...hub-openapi.security-chain-enabled` | `true` | Whether the plugin contributes its own `SecurityWebFilterChain` |

## Discovering contracts through the discovery client

When the gateway routes through a discovery client, the hub fetches the contract of every
discovered service, at `/v3/api-docs` by default:

```yaml
spring.cloud.gateway.server.webflux.discovery.locator.enabled: true
```

An application without a discovery client makes the discovery-based beans back off; the hub
then serves the statically configured contracts described below.

A service can declare where its document is, in its instance metadata, instead of letting the
hub probe the well-known SpringDoc paths — one HTTP call per service instead of up to three,
which matters on a large registry:

```yaml
eureka.instance.metadata-map.openapi_path: /v3/api-docs
```

The key read is `openapi_path`, in the metadata of the `ServiceInstance` — the Spring Cloud
Commons abstraction, not an Eureka one. Any discovery client filling that map declares the path
the same way.

### On Kubernetes

Instance metadata is built from the **Service** labels and annotations, so the declaration is
an annotation on the Service:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: service-a
  annotations:
    openapi_path: /v3/api-docs
```

*An annotation, not a label* — a label value must begin and end with an alphanumeric and
accepts only `-`, `_` and `.` in between, so `/v3/api-docs` is rejected by the API server.
*On the Service, not on the Deployment* — pod annotations are only read when
`spring.cloud.kubernetes.discovery.metadata.add-pod-annotations` is on, which is off by default
and needs RBAC on `pods`.

Two settings decide whether the hub sees the annotation at all, both of them defaults:

```yaml
spring.cloud.kubernetes.discovery.metadata:
  add-annotations: true      # off, and the annotation never reaches the metadata
  annotations-prefix:        # set, and the key becomes <prefix>openapi_path
```

The hub looks for the exact key `openapi_path`. Under a prefix, or with annotations left out,
it finds nothing and falls back on probing — the declaration is simply ignored, without error.

### Sizing the discovery

The gateway refreshes its routes on every discovery heartbeat, and each refresh probes the
services it does not know about yet. The defaults keep that cost constant whatever the size of
the registry: on a registry holding hundreds of services, probing them all at once saturates
the connection pool, and the probes then fail with `PoolAcquirePendingLimitException` while the
gateway stops routing.

Only a service that answered **"not there"** has its result cached. A service that could not be
reached, or refused to hand its document over, has said nothing about having one: it is probed
again on the next refresh, so a service that was down when the gateway started appears as soon
as it comes back, without waiting for `cache-ttl`.

Probing an instance stops as soon as it turns out to be unreachable, instead of trying the
remaining paths — they lead to the same instance and fail the same way. An unreachable service
therefore costs one `timeout`, not one per candidate path. A *refusal* does not stop it:
authorization is granted path by path, so a rule permitting `/v3/api-docs/**` serves
`/v3/api-docs` and answers `401` on `/v3/api-docs.json`, and the remaining paths are probed at
the cost of an immediate answer each.

The documents themselves are never buffered by the discovery: only the path each was found at
is kept. They are fetched, and their `servers` section rewritten, when the Swagger UI asks.

### A service that does not appear in the hub

A discovered service missing from the dropdown is either a service without a document — normal
and silent — or a service the hub could not read one from, which says so:

```
WARN  Route ALERT-SERVICE is left out of the OpenAPI hub: http://10.1.2.3:8080/v3/api-docs.json
      answered 401 UNAUTHORIZED. Its document is protected, and the hub reads it anonymously.
```

The same reason is logged once per `cache-ttl` rather than on every heartbeat, and again as
soon as it changes. Set `ch.nexsol.gateway.openapi.hub` to `debug` for every probe, path by
path.

The probes are anonymous and there is no way to give them a credential: a service whose
contract is worth aggregating serves it to the gateway. Note that a `401` says nothing about
the **service**, which is routed as usual — only its contract is missing from the hub.

A service missing from the dropdown without a word at all is a different matter — see
[below](#a-service-routed-without-the-discovery-client).

## Aggregating statically configured contracts

When
[routes-openapi](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md)
is also on the classpath, the contracts it is configured with appear in the Swagger UI beside
the discovered services — sources declared inline **and** those read through
`sources-locations`, so a contract served by a Config Server appears in the dropdown too.
Enabling both plugins is all it takes:

```yaml
spring.cloud.gateway.server.webflux.hub-openapi:
  enabled: true
  gateway-uri: http://localhost:8181
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources:
    - id: petstore
      uri: https://petstore3.swagger.io
      spec-url: https://petstore3.swagger.io/api/v3/openapi.json
      mode: PER_OPERATION
```

The source appears in the dropdown as `petstore`, served through the gateway at
`/v3/api-docs/petstore`. Each contract is proxied through the gateway with its `servers`
section rewritten, so "Try it out" targets the gateway and raises no CORS issue; a source
carrying a `path-prefix` is advertised with that prefix, so the call reaches the prefixed route
the generator created.

### A service routed without the discovery client

The hub builds its documentation routes from the routes the **discovery locator** produced, and
from those alone. A service routed any other way — declared by hand, or coming from
`routes-files`, `routes-configserver`, `routes-database` — is never probed and never appears.
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

`path-prefix` has to be the prefix the existing routes answer under, or "Try it out" calls
paths nothing serves.

## Which issuer the documents advertise

A service names, in its `openIdConnect` security scheme, the issuer **it** validates its own
traffic against — routinely an address internal to the cluster. Read in a browser, that address
resolves to nothing: the console shows a discovery URL nobody can reach, and no token can be
obtained to try an operation with. The gateway knows better, being configured with the issuers
it accepts on the traffic it routes.

| `security.issuer` | What the documents say |
| --- | --- |
| `DOCUMENT` | Whatever their service wrote, untouched. The default |
| `GATEWAY` | The issuers of this gateway, read from `spring.security.oauth2.resourceserver` — nothing to declare twice |

Both shapes are read, and neither has to be the one the console itself signs in through:

```yaml
spring.security.oauth2.resourceserver:
  multitenant:                 # each tenant contributes an issuer, under its id
    - id: local
      issuer-uri: http://localhost:9090
    - id: partner
      issuer-uri: https://partner.example.ch/realms/care
  # or, for a single issuer:
  jwt.issuer-uri: http://localhost:9090
```

With **one** issuer, every `openIdConnect` scheme keeps its name and only its discovery URL
changes. With **several**, each scheme becomes one scheme per tenant — `bearer-oidc` becomes
`bearer-oidc-local` and `bearer-oidc-partner` — and every requirement naming it becomes one
alternative per tenant. An OpenAPI `security` list is a disjunction, so the console offers the
tenants as the choice they are.

Only the discovery URL moves: the schemes, scopes and operations stay the service's own, and a
scheme that is not `openIdConnect` is left alone. Asking for `GATEWAY` on a gateway configured
with no issuer changes nothing, and says so at start-up.

## Spring Security

When Spring Security is on the classpath and the hub is enabled, the plugin contributes its own
`SecurityWebFilterChain` so the Swagger UI and the aggregated contracts stay reachable. Nothing
has to be declared.

| Path | What it serves |
| --- | --- |
| `springdoc.api-docs.path` (default `/v3/api-docs`), and its `.yaml` and `/swagger-config` variants | The contract of the gateway itself and the Swagger UI configuration |
| `/v3/api-docs/*` | The aggregated contracts, published as gateway routes named after each service |
| `springdoc.swagger-ui.path` (default `/swagger-ui.html`), `/swagger-ui/**`, `/webjars/swagger-ui/**` | The Swagger UI page and its assets |

The SpringDoc paths are read from the SpringDoc configuration, so a custom path is honoured.
The aggregated contracts are matched **one segment deep** and the assets are scoped to the
Swagger UI webjar, so no unrelated gateway route inherits the documentation permissions —
`/webjars/**` as a whole is never opened.

The chain is ordered at `HubApiSecurityAutoConfiguration.HUB_OPENAPI_CHAIN_ORDER`
(`Ordered.HIGHEST_PRECEDENCE + 400`), ahead of the chains an application usually declares from
`@Order(1)`. Two escape hatches: declare your own bean named `hubOpenapiSecurityWebFilterChain`,
or set `security-chain-enabled: false`.

> As with any `SecurityWebFilterChain` bean, its presence makes Spring Boot back off from its
> default "everything authenticated" chain. An application relying on that default must declare
> its own chains.

### When the console is there

A gateway also carrying the [console](../spring-cloud-gateway-ui/README.md) wants one answer to
"who may read this", not two. The hub declares the same paths to the console, whose chain is
ordered ahead of this one:

| Path | Under the console |
| --- | --- |
| The Swagger UI, its assets, `/swagger-config` and the aggregated contracts | Follow the console: open while it is open, behind its login once it is not |
| `springdoc.api-docs.path` and its `.json` / `.yaml` variants | Stay open |

The contract of the gateway itself stays open because the hub reads it over HTTP, on every
registered instance and its own included, with a client carrying no credentials. Closing it
would remove the gateway from its own hub rather than protect anything — what is exposed is the
shape of the API, not its data. Declare your own `hubOpenapiSecuredPaths` bean and the plugin
backs off.

## Auditing

With the [auditing plugin](../spring-cloud-gateway-audit/spring-cloud-gateway-audit-core/README.md)
on the classpath, the hub keeps its documentation endpoints out of the audit trail: a console
polling the contracts calls them over and over, and that traffic says nothing about what the
gateway routed. The dependency is optional.

The excluded paths are the ones listed above — the security chain and the audit exclusion read
the same resolved list. That includes `/v3/api-docs/*`, which **are** real proxied routes, so
excluding them takes genuinely routed traffic out of the trail. To keep it, set
`...audit.web-filter.exclude-paths` yourself: the list is additive, so what you configure is
kept and only the missing hub paths are appended.

The exclusion only applies to the global audit web filter. A route carrying the `Audit` gateway
filter is audited whatever this says.

## Sample

[gateway-hub-openapi](../spring-cloud-gateway-samples/gateway/gateway-hub-openapi/README.md) —
port `8203`, with `eureka` on `8761` and `service-a` on `8080`.
