# spring-cloud-gateway-metrics

Collects the per-route request figures the gateway UI plots, from a source you choose.

The default reads the meter registry of the running instance. Behind a load balancer that
answer is incomplete by construction — each instance only ever counted its own share of the
traffic, and the traffic view repolls through the balancer, so successive refreshes land on
different instances. A provider module replaces the source with one that reaches beyond the
local JVM.

## Modules

| Module | Description |
|--------|-------------|
| [spring-cloud-gateway-metrics-core](spring-cloud-gateway-metrics-core/README.md) | SPI, aggregation and the local meter registry source |
| [spring-cloud-gateway-metrics-prometheus](spring-cloud-gateway-metrics-prometheus/README.md) | Reads the consolidated figures from Prometheus |
| [spring-cloud-gateway-metrics-redis](spring-cloud-gateway-metrics-redis/README.md) | Each instance publishes its figures; the view sums them |
| [spring-cloud-gateway-metrics-discovery](spring-cloud-gateway-metrics-discovery/README.md) | Polls every instance registered in the service registry |

Add `-core` for the figures of one instance; add a provider on top to consolidate. A
provider declares its source ahead of the core, so it wins over the local one — the same
way an audit provider wins over the default publisher.

## Choosing a source

| | Local (default) | Prometheus | Redis | Discovery |
|---|---|---|---|---|
| Extra infrastructure | none | a Prometheus | a Redis | a service registry |
| Covers every instance | no | yes | yes | yes |
| Survives a restart | no | **yes** | no | no |
| Cost per refresh | none | 3 queries | 1 scan | 1 call per instance |
| Freshness | live | scrape interval | publish interval | live |

Prometheus is the only one that keeps history: the series outlive the JVM that produced
them, so a rolling restart does not reset the figures. The other two consolidate live
counters, which start again from zero when an instance is replaced.

## Configuration

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          metrics:
            enabled: true          # master switch (default true)
            provider:              # prometheus | redis | discovery ; unset = this instance only
            instance-id:           # defaults to the host name, the pod name on Kubernetes
            excluded-routes:
              - openapi-docs-.*    # the default
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master switch; when `false` no source is registered and the traffic view stays closed |
| `provider` | _(unset)_ | Source selector, read by the provider modules |
| `instance-id` | host name | Identifier shown next to the figures this instance produced |
| `excluded-routes` | `openapi-docs-.*` | Route ids left out; the whole id must match |

## Coverage

Every source reports **what its figures cover** along with the figures themselves, and the
UI shows it under the chart and on the home page tile:

```
this instance only (gateway-7f9c4)
every instance, from Prometheus
3 instances, via Redis
2 of 3 instances (the others did not answer)
```

A count means something different depending on which of these it is, so it travels with the
count rather than living in the documentation.
