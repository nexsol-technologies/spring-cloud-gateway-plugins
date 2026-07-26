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
```

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

Two services routinely declare the same paths &mdash; a patient service and a billing
service both exposing `/patients` would collide on the gateway. `path-prefix` moves a
contract aside, on the gateway side only:

```yaml
    - id: patients
      uri: https://patient-service.example.org
      spec-url: https://patient-service.example.org/v3/api-docs
      path-prefix: /patient-service
```

The operation `/patients` is then exposed as `/patient-service/patients`. The prefix exists
for callers only: it is removed again before the request is forwarded, so the backend still
receives the path its contract declares.

```
client            gateway route                       backend
GET /patient-service/patients
                  Path=/patient-service/patients
                  RewritePath=/patient-service(?<remaining>/?.*), ${remaining}   -> /patients
                  PrefixPath=/api/v3                                            -> /api/v3/patients
                                                       GET /api/v3/patients
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
"Try it out" calls `/patient-service/patients` and reaches the generated route.

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

The configured `metadata` and `filters` are applied to every generated route.

## Reloading

Besides the optional `update-interval`, the routes are regenerated on `/actuator/refresh` and
`/actuator/busrefresh` — see [Refreshing routes](../README.md#refreshing-routes).
