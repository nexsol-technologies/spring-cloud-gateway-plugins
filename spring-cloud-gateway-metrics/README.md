# spring-cloud-gateway-metrics

Collects the figures the [gateway console](../spring-cloud-gateway-ui/README.md) plots, from
a source you choose:

* **Per route** — how much traffic each route carries, how slow it is, how often it fails.
  Plotted by the traffic view.
* **Per instance** — memory, processor, threads and connection pools of each running
  gateway. Listed by the instances view.

The default source is the meter registry of the running instance. Behind a load balancer
that is only its own share of the traffic, and successive refreshes land on different
instances — add a provider module to consolidate them.

## Modules

| Module | What it does |
| --- | --- |
| [`-core`](spring-cloud-gateway-metrics-core/README.md) | SPI, aggregation and the local meter registry sources. Always required. |
| [`-prometheus`](spring-cloud-gateway-metrics-prometheus/README.md) | Reads the consolidated figures from Prometheus |
| [`-redis`](spring-cloud-gateway-metrics-redis/README.md) | Each instance publishes its figures; the views read them back |
| [`-discovery`](spring-cloud-gateway-metrics-discovery/README.md) | Polls every instance registered in the service registry |

A provider declares its sources ahead of the core ones, so they win over the local ones.
A provider implementing only one of the two contracts degrades cleanly: the core keeps its
local source for the other.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-metrics-core</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.metrics`.

```yaml
spring.cloud.gateway.server.webflux.metrics:
  # Source of the figures. Unset, each instance reports its own.
  provider: prometheus
  # Name this instance is reported under. Defaults to the host name (the pod name on Kubernetes).
  instance-id: gateway-1
  # Route ids left out of the figures; the whole id must match.
  excluded-routes:
    - openapi-docs-.*
  instance:
    # Publishes the Reactor Netty event loop and HTTP client counters.
    instrument-http-client: true
```

| Property | Default | What it does |
| --- | --- | --- |
| `...metrics.enabled` | `true` | Master switch; `false` registers no source and both views stay closed |
| `...metrics.provider` | — | `prometheus` \| `redis` \| `discovery`; unset reports this instance only |
| `...metrics.instance-id` | host name | Name shown next to the figures this instance produced |
| `...metrics.excluded-routes` | `[openapi-docs-.*]` | Route id patterns left out of the route figures |
| `...metrics.instance.enabled` | `true` | The per-instance figures; independent from the route ones |
| `...metrics.instance.instrument-http-client` | `false` | Publishes the event loop and HTTP client counters |

## Choosing a source

The two families do not rank the providers the same way.

**Route figures** — Prometheus is the only one that keeps history, so a rolling restart does
not reset the figures:

| | Local (default) | Prometheus | Redis | Discovery |
| --- | --- | --- | --- | --- |
| Extra infrastructure | none | a Prometheus | a Redis | a service registry |
| Covers every instance | no | yes | yes | yes |
| Survives a restart | no | **yes** | no | no |
| Cost per refresh | none | 3 queries | 1 scan | 1 call per instance |
| Freshness | live | scrape interval | publish interval | live |

**Instance figures** — a reading is native to its instance, so Discovery is the most direct;
Prometheus names its rows after the scrape target, so set `instance-label` when the
deployment publishes a better one:

| | Local (default) | Prometheus | Redis | Discovery |
| --- | --- | --- | --- | --- |
| Covers every instance | no | yes | yes | yes |
| Names instances by | `instance-id` | a Prometheus label | `instance-id` | `instance-id` |
| A stopped instance | — | drops after `stale-after` | drops when its key expires | drops immediately |
| Cost per refresh | none | 1 query | 1 scan | 1 call per instance |

## Instrumentation

The JVM and system figures are bound by Spring Boot. The gateway-specific ones are not:
Spring Cloud Gateway leaves that instrumentation off, and no amount of querying the meter
registry brings back a counter that was never registered.

```yaml
spring.cloud.gateway.server.webflux:
  httpclient.pool.metrics: true                 # connection pools
  metrics.instance.instrument-http-client: true # event loops and HTTP client
```

Both default to off because they add a metrics recorder to the pipeline of every connection,
which costs something on the data path. What is off is reported as such in the view, per
instance, rather than shown as an empty table.

## Coverage

Every source reports what its figures cover — `this instance only (gateway-7f9c4)`,
`every instance, from Prometheus`, `2 of 3 instances (the others did not answer)` — shown
under the chart, above the instance cards and on the home page tiles. A count means
something different depending on which of these produced it, so it travels with the count.

## Sample

[gateway-metrics](../spring-cloud-gateway-samples/gateway/gateway-metrics/README.md) — port
`8206`, with the three consolidating sources behind a profile each.
