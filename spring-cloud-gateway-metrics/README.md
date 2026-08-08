# spring-cloud-gateway-metrics

Collects the figures the gateway UI plots, from a source you choose. Two families, read
through two independent contracts:

* **Per route** &mdash; how much traffic each route carries, how slow it is, how often it
  fails. Plotted by the traffic view.
* **Per instance** &mdash; the memory, processor, threads and connection pools of each
  running gateway. Listed by the instances view.

The default reads the meter registry of the running instance. Behind a load balancer that
answer is incomplete by construction &mdash; each instance only ever counted its own share
of the traffic, and the views repoll through the balancer, so successive refreshes land on
different instances. A provider module replaces the sources with ones that reach beyond the
local JVM.

## Modules

| Module | Description |
|--------|-------------|
| [spring-cloud-gateway-metrics-core](spring-cloud-gateway-metrics-core/README.md) | SPI, aggregation and the local meter registry sources |
| [spring-cloud-gateway-metrics-prometheus](spring-cloud-gateway-metrics-prometheus/README.md) | Reads the consolidated figures from Prometheus |
| [spring-cloud-gateway-metrics-redis](spring-cloud-gateway-metrics-redis/README.md) | Each instance publishes its figures; the views read them back |
| [spring-cloud-gateway-metrics-discovery](spring-cloud-gateway-metrics-discovery/README.md) | Polls every instance registered in the service registry |

Add `-core` for the figures of one instance; add a provider on top to consolidate. A
provider declares its sources ahead of the core, so they win over the local ones &mdash;
the same way an audit provider wins over the default publisher.

## Choosing a source

The two families do not rank the providers the same way, which is the one thing worth
reading before picking one.

**For the route figures**, Prometheus wins:

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

**For the instance figures**, that ordering inverts:

| | Local (default) | Prometheus | Redis | Discovery |
|---|---|---|---|---|
| Covers every instance | no | yes | yes | yes |
| Names instances by | `instance-id` | a Prometheus label | `instance-id` | `instance-id` |
| A stopped instance | &mdash; | drops after `stale-after` | drops when its key expires | drops immediately |
| Cost per refresh | none | 1 query | 1 scan | 1 call per instance |

A per-instance reading is native to the instance, so Discovery is the most direct: the
registry hands out exactly one row per live instance. The property that makes Prometheus
the best route source &mdash; series outliving their JVM &mdash; is the awkward one here,
since a list of instances is meant to name the ones running now; `stale-after` is what
takes care of that. Prometheus also names its rows after the scrape target rather than
after `instance-id`, so set `instance-label` when the deployment publishes a better one.

The two contracts are independent, so a provider that implements only one of them degrades
cleanly: the core keeps its local source for the other, and the coverage says so.

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
            instance:
              enabled: true              # the per-instance figures (default true)
              instrument-http-client: false
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master switch; when `false` no source is registered and both views stay closed |
| `provider` | _(unset)_ | Source selector, read by the provider modules |
| `instance-id` | host name | Identifier shown next to the figures this instance produced |
| `excluded-routes` | `openapi-docs-.*` | Route ids left out of the route figures; the whole id must match |
| `instance.enabled` | `true` | The per-instance figures; independent from the route ones both ways round |
| `instance.instrument-http-client` | `false` | Publishes the Reactor Netty event loop and HTTP client counters |

## Instrumentation

The JVM and system figures are there out of the box: Spring Boot binds them. The figures
that are specific to a gateway are not, because Spring Cloud Gateway leaves that
instrumentation off, and **no amount of querying the meter registry brings a counter that
was never registered**. Two switches, of two different kinds:

```yaml
spring.cloud.gateway.server.webflux:
  httpclient.pool.metrics: true                 # connection pools
  metrics.instance.instrument-http-client: true # event loops and HTTP client
```

The first is the gateway's own property, read while the HTTP client is being built &mdash;
too early for any bean to influence, which is why this plugin reads it rather than sets it.
The second registers an `HttpClientCustomizer`, the only way to reach an instrumentation
the gateway never exposes.

Both default to off, and this is the one place where the metrics plugin changes what the
gateway *does* rather than only reading what it already publishes: a metrics recorder is
added to the pipeline of every connection, which costs something on the data path. Turning
that on belongs to whoever operates the gateway.

What is off is reported as such, per instance, rather than shown as an empty table &mdash;
an empty pool list would otherwise read as "no downstream called yet", which calls for
waiting rather than for a configuration change.

## Coverage

Every source reports **what its figures cover** along with the figures themselves, and the
UI shows it under the chart, above the instance cards and on the home page tiles:

```
this instance only (gateway-7f9c4)
every instance, from Prometheus
3 instances, via Redis
2 of 3 instances (the others did not answer)
no instance reported to Prometheus recently
```

A count means something different depending on which of these it is, so it travels with the
count rather than living in the documentation.
