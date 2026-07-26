# spring-cloud-gateway-routes-configserver

Sources Spring Cloud Gateway route definitions from JSON and YAML files served over HTTP by a
[Spring Cloud Config Server](https://docs.spring.io/spring-cloud-config/reference/server.html)
and aggregates them into the route locator. Routes stay declared as files under version control
(the Config Server backing repository), while the gateway fetches them at runtime — no redeploy
to change routing.

The files are fetched through the Config Server
[plain-text resource endpoint](https://docs.spring.io/spring-cloud-config/reference/server/resources.html)
(`/{name}/{profile}/{label}/{path}`), which serves the raw file content.

```xml
<dependency>
    <groupId>ch.nexsol.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-configserver</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

Two complementary ways to point at the route files — use either or both:

```yaml
spring.cloud.gateway.server.webflux.routes-configserver:
  enabled: true
  update-interval: 30s                # optional; re-fetch every source on this fixed delay
  # 1. Full URLs to individual route files
  urls:
    - http://localhost:8888/gateway/default/main/orders-routes.yaml
    - http://localhost:8888/gateway/default/main/billing-routes.json
  # 2. Config Server coordinates: a base "directory" + an explicit list of files
  config-server:
    uri: http://localhost:8888        # Config Server base URI
    name: gateway                     # {name}    (application)
    profile: default                  # {profile}
    label: main                       # {label}   (optional, e.g. git branch/tag)
    files:                            # each resolved as /{name}/{profile}/{label}/{file}
      - routes/orders.yaml
      - routes/billing.yaml
```

> The Config Server has **no directory-listing API**: its plain-text endpoint serves one file per
> request. A "directory" is therefore expressed as the coordinate above plus an explicit `files`
> list — each entry is fetched individually.

A source that is transiently unreachable is logged and the previous route snapshot is kept.

## Reloading

The routes are re-fetched from the Config Server in these cases:

- **At startup** (always).
- **Periodically**, when `update-interval` is set.
- **On demand** via `/actuator/refresh` and `/actuator/busrefresh` (Spring Cloud Bus). Both publish
  a `RefreshScopeRefreshedEvent`, handled by the shared refresher in
  [spring-cloud-gateway-routes-core](../README.md#refreshing-routes), which re-fetches every source
  and rebuilds the gateway route table.

The `/refresh` support is active only when the Spring Cloud Config client (`spring-cloud-context`)
is on the classpath — i.e. whenever the gateway is itself a Config Server client. Expose the
endpoints as usual:

```yaml
management.endpoints.web.exposure.include: refresh, busrefresh
```

> Note: `/refresh` and `/busrefresh` re-fetch the **content** of the configured files. The list of
> URLs (and Config Server coordinates) is read at startup; changing that list requires a restart.

## File format

Each fetched file mirrors the standard `spring.cloud.gateway` route configuration and uses the
exact same format as the [file-based locator](../spring-cloud-gateway-routes-files/README.md): a
top-level array of routes or an object with a `routes` array, with predicates and filters in either
the shorthand string form or the object form. The file format (JSON vs YAML) is detected from the
URL file extension.

```yaml
routes:
  - id: orders_route
    uri: https://orders.example.org
    order: 1
    predicates:
      - Cookie=mycookie,mycookievalue   # shorthand form
      - name: Path                      # object form
        args:
          pattern: /orders/**
    filters:
      - AddRequestHeader=X-Request-Foo,Bar
      - name: Retry
        args:
          retries: "3"
    metadata:
      tier: gold
```
