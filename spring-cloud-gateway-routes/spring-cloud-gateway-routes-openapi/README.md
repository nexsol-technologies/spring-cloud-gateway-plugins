# spring-cloud-gateway-routes-openapi

Generates gateway routes from an OpenAPI contract and aggregates them into the route locator.
Inspired by
[openapi-route-definition-locator](https://github.com/jbretsch/openapi-route-definition-locator).

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-openapi</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.routes-openapi`.

```yaml
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  update-interval: 5m                   # omit to load once at startup
  sources:
    - id: books
      uri: https://book-service.example.org  # backend target: only scheme/host/port is used
      spec-url: https://book-service.example.org/v3/api-docs
      mode: PER_OPERATION                    # PER_OPERATION | AGGREGATED | NO_ROUTE
      path-prefix: /book-service             # gateway side: callers add it, it is removed before forwarding
      # base-path: /api/v3                   # backend side; omit to derive it from the contract servers
      validate: true                         # also hold the traffic against this contract
      metadata:
        team: books
      filters:
        - Retry=3
```

| Property | Default | What it does |
| --- | --- | --- |
| `...routes-openapi.enabled` | `false` | Activates the source |
| `...routes-openapi.update-interval` | — | Fixed delay between two reloads; unset loads once at startup |
| `...routes-openapi.sources` | — | Sources declared inline |
| `...routes-openapi.sources-locations` | — | Documents declaring further sources |

Per source:

| Property | Default | What it does |
| --- | --- | --- |
| `id` | — | Prefix of every generated route id |
| `uri` | — | Backend target; only scheme, host and port are used |
| `spec-url` | — | Where the contract is read from (`classpath:`, `file:`, `http(s)`) |
| `mode` | `AGGREGATED` | `PER_OPERATION`, `AGGREGATED` or `NO_ROUTE` |
| `path-prefix` | — | Prefix callers use, removed before forwarding |
| `base-path` | first `servers` entry | Prefix added when forwarding to the backend |
| `validate` | `false` | Attaches the `OpenapiValidation` filter with this source's contract |
| `metadata` | — | Carried onto every generated route |
| `filters` | — | Applied to every generated route |

## Generation modes

| Mode | What it generates |
| --- | --- |
| `PER_OPERATION` | One route per operation, with `Path=<path>` and `Method=<verb>`. Route id is `<source-id>_<operationId>`, or a sanitised `<source-id>_<method>_<path>` when the contract declares no `operationId` |
| `AGGREGATED` | A single route per source, with a `Path` predicate listing every path and a `Method` predicate listing every verb |
| `NO_ROUTE` | Nothing — the source is declared for its contract alone, see below |

### A contract without its routes

`mode: NO_ROUTE` declares a source so its contract joins the aggregated Swagger UI of
[spring-cloud-gateway-hub-openapi](../../spring-cloud-gateway-hub-openapi/README.md), next to
the services that plugin discovers on its own. That is the answer for a service whose routes
are already declared elsewhere — by hand, by the discovery locator, in a route file, in the
database — and `NO_ROUTE` is what keeps this plugin from adding a second set of routes in
front of a backend that already has some.

```yaml
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources:
    - id: alert-service
      spec-url: https://alert-service.internal/v3/api-docs
      mode: NO_ROUTE
      # The prefix the routes you already declared answer under, so "Try it out"
      # calls them rather than the bare contract paths.
      path-prefix: /ALERT-SERVICE
```

Only `id`, `spec-url` and `path-prefix` are read; the document is not even fetched by this
plugin — that is the hub's business, when the console asks for it.

## The two path settings

Two services routinely declare the same paths — a book service and a billing service both
exposing `/books` would collide on the gateway. `path-prefix` moves a contract aside, on the
gateway side only:

| | Side | Effect |
| --- | --- | --- |
| `path-prefix` | gateway | Added to the paths clients call, removed before forwarding |
| `base-path` | backend | Prepended when forwarding, since contract paths are relative to the document `servers` |

```
client            gateway route                       backend
GET /book-service/books
                  Path=/book-service/books
                  RewritePath=/book-service(?<remaining>/?.*), ${remaining}     -> /books
                  PrefixPath=/api/v3                                            -> /api/v3/books
                                                       GET /api/v3/books
```

`RewritePath` rather than `StripPrefix`, so the route shown in the console names the prefix it
removes instead of removing whatever segment sits first.

`base-path` unset is derived from the first `servers` entry of the contract (its path
component; the server host is ignored); `base-path: ""` adds no prefix at all.

When the [OpenAPI hub](../../spring-cloud-gateway-hub-openapi/README.md) is also present, the
contract it serves advertises the gateway **with** the prefix, so "Try it out" calls
`/book-service/books` and reaches the generated route.

## Validating the traffic against the same contract

Generating routes from a contract validates nothing: the routes match the paths the contract
declares, and whatever clients then send is forwarded as is. `validate: true` closes that gap
by attaching the `OpenapiValidation` filter of
[spring-cloud-gateway-openapi-validation](../../spring-cloud-gateway-openapi-validation/README.md),
which must be on the classpath.

The filter is given the `spec-url` and the `path-prefix` of the source, so the two can never
drift apart, and it is placed **ahead of every other filter** — a request breaking the
contract is denied before a retry or a rate limiter budget has been spent on it.

There is no metadata that turns validation on; `metadata` is only carried onto the generated
route. To hold the traffic against a *different* document — a stricter contract, say — leave
`validate` off and declare the filter yourself:

```yaml
      filters:
        - OpenapiValidation=classpath:openapi/bookstore-strict.yaml,/book-service
```

What happens to a message that breaks its contract — denied, or forwarded and recorded — is
configured per direction in the validation plugin, not here.

## Sources declared in documents

`sources-locations` points at documents declaring further sources, added to the inline ones:

```yaml
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources-locations:
    - classpath:openapi/*-apis.yaml
    - file:/etc/gateway/partner-apis.yaml
    - http://localhost:8888/gateway/default/main/team-apis.yaml   # a Config Server
  sources:                              # still available, and read first
    - id: petstore
      uri: https://petstore.example.org
      spec-url: https://petstore.example.org/v3/api-docs
```

A location is anything the Spring resource resolver understands, patterns included. An
`http(s)` location is how a document served by a Config Server is reached, over its plain-text
resource endpoint — and it is the **only** way Config Server can carry OpenAPI sources here:
the [routes-configserver](../spring-cloud-gateway-routes-configserver/README.md) plugin parses
route definitions, not sources, and would silently ignore a `sources:` document.

A document is written exactly like the inline configuration — an array of sources, or an
object with a `sources` array. Documents are re-read on every reload, and one that cannot be
read or parsed is **skipped with a warning** rather than dropping the sources the others
declared.

## Reloading

Besides `update-interval`, the routes are regenerated on `/actuator/refresh` and
`/actuator/busrefresh` — see [Refreshing routes](../README.md#refreshing-routes).

## Sample

[gateway-routes-all](../../spring-cloud-gateway-samples/gateway/gateway-routes-all/README.md)
— port `8210`.
