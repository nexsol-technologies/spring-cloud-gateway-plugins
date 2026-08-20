# spring-cloud-gateway-routes-files

Sources route definitions from JSON and YAML files and aggregates them into the route
locator. Built for GitOps: routes are files a CI/CD pipeline can validate, review and deploy.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-files</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.routes-files`.

```yaml
spring.cloud.gateway.server.webflux.routes-files:
  enabled: true
  # Reload when a watched file changes. file: locations only — a classpath: entry
  # inside a jar has nothing to watch.
  watch: true
  locations:
    - classpath:gateway-routes/*.yaml
    - file:./config/routes/*.json
```

| Property | Default | What it does |
| --- | --- | --- |
| `...routes-files.enabled` | `false` | Activates the source; nothing is read until it is `true` |
| `...routes-files.locations` | — | Spring resource patterns the route files are read from |
| `...routes-files.watch` | `false` | Reloads when a watched file changes (`file:` locations only) |

## File format

The file mirrors the standard `spring.cloud.gateway.server.webflux` route configuration:
either a top-level array of routes, or an object with a `routes` array. Predicates and
filters accept both the shorthand string form and the object form.

```yaml
routes:
  - id: after_route
    uri: https://example.org
    order: 1
    predicates:
      - Cookie=mycookie,mycookievalue   # shorthand form
      - name: Path                      # object form
        args:
          pattern: /api/**
    filters:
      - AddRequestHeader=X-Request-Foo,Bar
      - name: Retry
        args:
          retries: "3"
    metadata:
      tier: gold
      public: true                      # exempt from Spring Security, see routes-security
```

## Reloading

Besides `watch`, the routes are reloaded on `/actuator/refresh` and `/actuator/busrefresh` —
see [Refreshing routes](../README.md#refreshing-routes).

## Sample

[gateway-routes-files](../../spring-cloud-gateway-samples/gateway/gateway-routes-files/README.md)
— port `8207`.
