# spring-cloud-gateway-service-graph-prometheus

Reads the graph this gateway publishes back from Prometheus, so the view shows the whole
gateway rather than the instance that answered. The only source of the three counting ones
that keeps history across a restart.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-prometheus</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

The gateway still has to be scraped: the counter is a Micrometer meter, so this needs
`micrometer-registry-prometheus` and the `prometheus` actuator endpoint exposed, like any
other metric.

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.service-graph`.

```yaml
spring.cloud.gateway.server.webflux.service-graph:
  provider: prometheus
  prometheus:
    url: http://prometheus:9090
    selector: 'job="gateway"'    # restrict the series to this gateway
    timeout: 5s
    # Optional: raises the buffering ceiling for these calls alone.
    max-response-size: 2MB
```

| Property | Default | What it does |
| --- | --- | --- |
| `...service-graph.prometheus.url` | — | Base URL of the Prometheus server |
| `...service-graph.prometheus.selector` | — | Extra label matchers, written without the braces |
| `...service-graph.prometheus.meter` | `gateway_service_graph_calls_total` | Name of the counter to read |
| `...service-graph.prometheus.timeout` | `5s` | How long to wait before reporting no data |
| `...service-graph.prometheus.max-response-size` | — | Largest answer read; unset keeps the ceiling of `spring.http.codecs.max-in-memory-size` |
| `...service-graph.prometheus.username` / `.password` | — | Basic credentials |
| `...service-graph.prometheus.token` | — | Bearer token (Thanos, Mimir, OpenShift monitoring) |

> **Leave `selector` empty only when this gateway is the sole publisher of the counter.** A
> shared Prometheus otherwise draws the edges of every gateway as one graph — wrong in a way
> nothing on the page would reveal.

Credentials are read once at startup and set on a client dedicated to Prometheus, never
through a `WebClientCustomizer` that would put them on every client of the application. For
mTLS, OAuth2 client credentials or a rotating service account token, declare a
`prometheusServiceGraphWebClient` bean and it is used instead.

## One query

The counter carries the caller, the service, the route and the outcome as labels, so a single
instant query returns every edge:

```promql
sum by (caller, service, route, outcome) (gateway_service_graph_calls_total{job="gateway"})
```

The outcome is only read to tell the failures apart; the samples of one pair are summed back
together, so the edges come out exactly as the local source would have reported them.

## When it cannot answer

The coverage says why, because a page reporting nothing looks the same whether the gateway is
quiet or the credentials expired:

| Coverage | Meaning |
| --- | --- |
| `every instance, from Prometheus` | The query was answered |
| `… — authentication refused (401)` | The credentials were rejected |
| `… — refused with 503` | The server answered with an error |
| `… — unreachable` | It could not be reached at all |

## Sample

[gateway-full](../../spring-cloud-gateway-samples/gateway/gateway-full/README.md) — port
`8181`.
