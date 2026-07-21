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
