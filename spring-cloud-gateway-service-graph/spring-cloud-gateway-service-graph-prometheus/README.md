# spring-cloud-gateway-service-graph-prometheus

Reads the graph this gateway publishes back from Prometheus, so the view shows the whole
gateway rather than the instance that answered.

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-prometheus</artifactId>
</dependency>
```

```yaml
spring.cloud.gateway.server.webflux:
  service-graph:
    provider: prometheus
    prometheus:
      url: http://prometheus:9090
      selector: 'job="gateway"'    # restrict the series to this gateway
      timeout: 5s
```

The gateway still has to be scraped: the counter is a Micrometer meter, so this module
needs `micrometer-registry-prometheus` and the `prometheus` actuator endpoint exposed, like
any other metric.

## One query

The counter carries the caller, the service, the route and the outcome as labels, so a
single instant query returns every edge:

```promql
sum by (caller, service, route, outcome) (gateway_service_graph_calls_total{job="gateway"})
```

The outcome is only read to tell the failures apart; the samples of one pair are summed
back together, so the edges come out exactly as the local source would have reported them.

## Why the selector matters

Leave `selector` empty only when this gateway is the sole publisher of the counter. A
shared Prometheus otherwise draws the edges of every gateway as one graph — wrong in a way
nothing on the page would reveal.

## Authentication

`username` / `password` for Basic, or `token` for a bearer (Thanos, Mimir, the OpenShift
monitoring stack). Both are read once, at startup, and set on a client dedicated to
Prometheus rather than through a `WebClientCustomizer` that would put them on every client
of the application.

A Prometheus needing more — mTLS, OAuth2 client credentials, a service account token that
rotates — is served by declaring a `prometheusServiceGraphWebClient` bean, which is used
instead.

## When it cannot answer

The coverage says why, because a page reporting nothing looks the same whether the gateway
is quiet or the credentials expired:

| Coverage | Meaning |
|---|---|
| `every instance, from Prometheus` | the query was answered |
| `… — authentication refused (401)` | the credentials were rejected |
| `… — refused with 503` | the server answered with an error |
| `… — unreachable` | it could not be reached at all |
