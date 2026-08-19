# spring-cloud-gateway-service-graph-tempo

Reads the service graph Tempo derives from the spans it collected — the only source that
shows an edge the gateway never carried.

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-tempo</artifactId>
</dependency>
```

```yaml
spring.cloud.gateway.server.webflux:
  service-graph:
    provider: tempo
    tempo:
      url: http://mimir:9009/prometheus   # NOT the URL of Tempo — see below
      selector: 'namespace="prod"'
      timeout: 5s
```

## The URL is not Tempo's

Tempo serves traces; it does not serve a service graph. What builds one is its
**metrics-generator**, which derives the edges from the spans it sees and writes them as
Prometheus series — `traces_service_graph_request_total{client, server}` and
`traces_service_graph_request_failed_total{...}`. Grafana's own service graph panel reads
them from there, and so does this module.

So `url` points at the Prometheus, Mimir or Thanos the generator remote-writes to, and the
generator has to be enabled on the Tempo side (`metrics_generator.processor.service_graphs`)
with the gateway among the tenants it processes.

That is also why this module depends on `-prometheus`: the two read the same API and share
its query runner rather than carrying two copies of it. They ask for different series, of
different meaning, which is why they are two modules and not one.

## Two queries

```promql
sum by (client, server) (traces_service_graph_request_total{namespace="prod"})
sum by (client, server) (traces_service_graph_request_failed_total{namespace="prod"})
```

The failures are a series of their own, so a pair with no failure is simply absent from the
second vector and its edge reports none. Both label names are configurable
(`client-label`, `server-label`) for a generator that names them otherwise.

## What it does and does not give you

**It sees every edge**, including two services calling each other directly, which no
counter of the gateway can know. In a topology where every call transits the gateway, it
mostly confirms what the other sources already report.

**It carries no route.** The gateway is one hop among others here, and most edges never
went through one — so `GraphEdge.routeId` is `null`, and the question "through which route"
belongs to the other sources.

**It is exactly as complete as your instrumentation.** A service emitting no span is a
service the graph does not know, and its absence looks like silence rather than like a gap.

## When it cannot answer

The coverage says why: `every service, from Tempo`, or that same line followed by
`authentication refused (401)`, `refused with 503` or `unreachable`.
