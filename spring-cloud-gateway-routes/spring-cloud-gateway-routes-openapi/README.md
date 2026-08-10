# spring-cloud-gateway-routes-openapi

Generates Spring Cloud Gateway routes from an OpenAPI contract and aggregates them into the
route locator. Inspired by
[openapi-route-definition-locator](https://github.com/jbretsch/openapi-route-definition-locator).

```xml
<dependency>
    <groupId>ch.nexsol.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-openapi</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

```yaml
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  update-interval: 5m                 # optional periodic reload; omit to load once at startup
  sources:
    - id: petstore
      uri: https://petstore.example.org   # backend target: only scheme/host/port is used
      spec-url: https://petstore.example.org/v3/api-docs
      mode: PER_OPERATION             # or AGGREGATED
      # base-path: /api/v3            # optional; omit to derive it from the contract servers
      metadata:
        team: pets
      filters:
        - Retry=3
      # validate: true                # also hold the traffic against this contract
```

### Validating the traffic against the same contract

Generating routes from a contract does not validate anything: the routes match the paths the
contract declares, and whatever the clients then send is forwarded as is. Setting
`validate: true` on a source closes that gap by attaching the `OpenapiValidation` filter of
[spring-cloud-gateway-openapi-validation](../../spring-cloud-gateway-openapi-validation/README.md),
which needs to be on the classpath:

```yaml
    - id: bookstore
      uri: http://localhost:8080
      spec-url: classpath:openapi/bookstore.yaml
      path-prefix: /book-service
      validate: true
      filters:
        - Retry=3
```

The filter is given the `spec-url` and the `path-prefix` of the source, so the two can never
drift apart, and it is placed **ahead of every other filter** — a request that breaks the
contract is denied before a retry or a rate limiter budget has been spent on it.

There is no metadata that turns validation on. `metadata` is only carried onto the generated
route; nothing reads it.

To hold the traffic against a *different* document than the routes were generated from — a
stricter contract, say — leave `validate` off and declare the filter yourself, since it takes
the contract location as its own argument:

```yaml
      filters:
        - OpenapiValidation=classpath:openapi/bookstore-strict.yaml,/book-service
```

What happens to a message that breaks its contract — denied, or forwarded and recorded — is
configured per direction in the validation plugin, not here.

### Sources declared in documents

Sources do not have to live in the application configuration. `sources-locations` points at
documents declaring further sources, which are added to the inline ones:

```yaml
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources-locations:
    - classpath:openapi/internal-apis.yaml
    - file:/etc/gateway/partner-apis.yaml
    - http://localhost:8888/gateway/default/main/team-apis.yaml
  sources:                            # still available, and read first
    - id: petstore
      uri: https://petstore.example.org
      spec-url: https://petstore.example.org/v3/api-docs
```

A location is anything the Spring resource resolver understands — `classpath:`, `file:` or
an `http(s)` URL, patterns included (`classpath:openapi/*-apis.yaml`). An `http(s)` location
is how a document **served by a Config Server** is reached, over its plain-text resource
endpoint `/{name}/{profile}/{label}/{path}`:

```yaml
    - http://localhost:8888/gateway/default/main/team-apis.yaml
```

Note this is the *only* way Config Server can carry OpenAPI sources through this plugin: the
[routes-configserver](../spring-cloud-gateway-routes-configserver/README.md) plugin parses
route definitions, not sources, and would silently ignore a `sources:` document.

A document is written exactly like the inline configuration — an array of sources, or an
object with a `sources` array:

```yaml
sources:
  - id: partner
    uri: https://partner.example.org
    spec-url: https://partner.example.org/v3/api-docs
    mode: PER_OPERATION
    base-path: /api/v3
    metadata:
      team: partners
    filters:
      - Retry=3
```

Documents are re-read on every reload, so a change is picked up by the `update-interval` or
by `/actuator/refresh`. A document that cannot be read or parsed is **skipped with a warning**
rather than dropping the sources the others declared — the same isolation the generator
applies per source.

### Telling two contracts apart: `path-prefix`

Two services routinely declare the same paths &mdash; a book service and a billing
service both exposing `/books` would collide on the gateway. `path-prefix` moves a
contract aside, on the gateway side only:

```yaml
    - id: books
      uri: https://book-service.example.org
      spec-url: https://book-service.example.org/v3/api-docs
      path-prefix: /book-service
```

The operation `/books` is then exposed as `/book-service/books`. The prefix exists
for callers only: it is removed again before the request is forwarded, so the backend still
receives the path its contract declares.

```
client            gateway route                       backend
GET /book-service/books
                  Path=/book-service/books
                  RewritePath=/book-service(?<remaining>/?.*), ${remaining}     -> /books
                  PrefixPath=/api/v3                                            -> /api/v3/books
                                                       GET /api/v3/books
```

`RewritePath` rather than `StripPrefix`, so the route shown in the UI names the prefix it
removes instead of removing whatever segment sits first.

Do not confuse the two path settings:

| | Side | Effect |
| --- | --- | --- |
| `path-prefix` | gateway | added to the paths clients call, removed before forwarding |
| `base-path` | backend | prepended when forwarding, since contract paths are relative to the document `servers` |

When [spring-cloud-gateway-hub-openapi](../../spring-cloud-gateway-hub-openapi/README.md) is
also present, the contract it serves advertises the gateway **with** the prefix, so
"Try it out" calls `/book-service/books` and reaches the generated route.

### Backend base path

The OpenAPI operation paths are relative to the document `servers` base path, so the
generated routes prepend that base path (as a `PrefixPath` filter) to reach the real
backend endpoint. `uri` provides the backend **host** (scheme/host/port — its path is
ignored by the gateway), while the base path comes from the contract.

- `base-path` unset → derived from the first `servers` entry of the contract (its path
  component; the server host is ignored).
- `base-path: /api/v3` → forces that value.
- `base-path: ""` → adds no prefix.

## Generation modes

- **PER_OPERATION** — one route per OpenAPI operation, each with `Path=<path>` and
  `Method=<verb>` predicates. The route id is `<source-id>_<operationId>` (or a sanitized
  `<source-id>_<method>_<path>` when no `operationId` is present).
- **AGGREGATED** — a single route per source, with a `Path` predicate listing every path and
  a `Method` predicate listing every HTTP method.

The configured `metadata` and `filters` are applied to every generated route, as is the
`OpenapiValidation` filter when the source sets `validate: true`.

## Reloading

Besides the optional `update-interval`, the routes are regenerated on `/actuator/refresh` and
`/actuator/busrefresh` — see [Refreshing routes](../README.md#refreshing-routes).
