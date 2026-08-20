# spring-cloud-gateway-service-graph-tempo

Reads the service graph Tempo derives from the spans it collected — the only source that
shows an edge the gateway never carried.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-tempo</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

It depends on
[`-prometheus`](../spring-cloud-gateway-service-graph-prometheus/README.md): the two read the
same API and share its query runner. They ask for different series, of different meaning,
which is why they are two modules and not one.

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.service-graph`.

```yaml
spring.cloud.gateway.server.webflux.service-graph:
  provider: tempo
  tempo:
    # NOT the URL of Tempo — see below.
    url: http://mimir:9009/prometheus
    selector: 'namespace="prod"'
    timeout: 5s
```

| Property | Default | What it does |
| --- | --- | --- |
| `...service-graph.tempo.url` | — | Base URL of the Prometheus-compatible store the metrics-generator writes to |
| `...service-graph.tempo.selector` | — | Extra label matchers, written without the braces |
| `...service-graph.tempo.request-metric` | `traces_service_graph_request_total` | Series carrying the calls |
| `...service-graph.tempo.failed-metric` | `traces_service_graph_request_failed_total` | Series carrying the failures |
| `...service-graph.tempo.client-label` / `.server-label` | `client` / `server` | Labels naming the two ends of an edge |
| `...service-graph.tempo.timeout` | `5s` | How long to wait before reporting no data |
| `...service-graph.tempo.username` / `.password` / `.token` | — | Credentials, as for the Prometheus source |

> **`url` is not Tempo's.** Tempo serves traces, not a service graph. What builds one is its
> **metrics-generator**, which derives the edges from the spans and writes them as Prometheus
> series. So `url` points at the Prometheus, Mimir or Thanos the generator remote-writes to,
> and `metrics_generator.processor.service_graphs` has to be enabled on the Tempo side with
> the gateway among the tenants it processes.

## Two queries

```promql
sum by (client, server) (traces_service_graph_request_total{namespace="prod"})
sum by (client, server) (traces_service_graph_request_failed_total{namespace="prod"})
```

The failures are a series of their own, so a pair with no failure is simply absent from the
second vector and its edge reports none.

## What it does and does not give you

* **It sees every edge**, including two services calling each other directly, which no counter
  of the gateway can know. Where every call transits the gateway, it mostly confirms what the
  other sources already report.
* **It carries no route.** The gateway is one hop among others here and most edges never went
  through one, so `GraphEdge.routeId` is `null`.
* **It is exactly as complete as your instrumentation.** A service emitting no span is a
  service the graph does not know, and its absence looks like silence rather than like a gap.

## When it cannot answer

The coverage says why: `every service, from Tempo`, or that same line followed by
`authentication refused (401)`, `refused with 503` or `unreachable`.
