# spring-cloud-gateway-routes-configserver

Sources route definitions from JSON and YAML files served over HTTP by a
[Spring Cloud Config Server](https://docs.spring.io/spring-cloud-config/reference/server.html).
Routes stay under version control in the Config Server backing repository, while the gateway
fetches them at runtime — no redeploy to change routing.

The files are fetched through the Config Server
[plain-text resource endpoint](https://docs.spring.io/spring-cloud-config/reference/server/resources.html)
(`/{name}/{profile}/{label}/{path}`), which serves the raw file content.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-configserver</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.routes-configserver`. Two
complementary ways to point at the route files — use either or both.

```yaml
spring.cloud.gateway.server.webflux.routes-configserver:
  enabled: true
  update-interval: 30s                # omit to fetch once at startup
  # 1. Full URLs to individual route files
  urls:
    - http://localhost:8888/gateway/default/main/orders-routes.yaml
    - http://localhost:8888/gateway/default/main/billing-routes.json
  # 2. Config Server coordinates: a base "directory" plus an explicit list of files
  config-server:
    uri: http://localhost:8888        # Config Server base URI
    name: gateway                     # {name}    (application)
    profile: default                  # {profile}
    label: main                       # {label}   (git branch or tag)
    files:                            # each resolved as /{name}/{profile}/{label}/{file}
      - routes/orders.yaml
      - routes/billing.yaml
```

| Property | Default | What it does |
| --- | --- | --- |
| `...routes-configserver.enabled` | `false` | Activates the source |
| `...routes-configserver.update-interval` | — | Fixed delay between two re-fetches; unset fetches once at startup |
| `...routes-configserver.urls` | — | Full URLs of individual route files |
| `...routes-configserver.config-server.uri` | — | Config Server base URI |
| `...routes-configserver.config-server.name` | — | The `{name}` coordinate (application) |
| `...routes-configserver.config-server.profile` | `default` | The `{profile}` coordinate |
| `...routes-configserver.config-server.label` | — | The `{label}` coordinate (git branch or tag) |
| `...routes-configserver.config-server.files` | — | Files fetched under that coordinate, one request each |

> The Config Server has **no directory-listing API** — its plain-text endpoint serves one file
> per request. A "directory" is therefore the coordinate above plus an explicit `files` list.

A source that is transiently unreachable is logged, and the previous route snapshot is kept.

## Reloading

Re-fetched at startup, on `update-interval` when set, and on `/actuator/refresh` /
`/actuator/busrefresh` — see [Refreshing routes](../README.md#refreshing-routes).

> `/refresh` re-fetches the **content** of the configured files. The list of URLs and the
> Config Server coordinates are read at startup; changing that list needs a restart.

## File format

Identical to the [file-based source](../spring-cloud-gateway-routes-files/README.md#file-format):
a top-level array of routes or an object with a `routes` array, predicates and filters in
either form. JSON or YAML is detected from the URL file extension.

```yaml
routes:
  - id: orders_route
    uri: https://orders.example.org
    predicates:
      - Path=/orders/**
    filters:
      - AddRequestHeader=X-Request-Foo,Bar
    metadata:
      tier: gold
```

## Sample

[gateway-routes-all](../../spring-cloud-gateway-samples/gateway/gateway-routes-all/README.md)
— port `8210`, with the `config-server` sample on `8888`.
