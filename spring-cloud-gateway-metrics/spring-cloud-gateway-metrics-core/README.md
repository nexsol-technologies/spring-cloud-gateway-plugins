# spring-cloud-gateway-metrics-core

The two SPIs the metrics views read through, the aggregation, and the sources reading the
meter registry of the running instance. Every provider module builds on it.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-metrics-core</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

The gateway console already depends on it; add it explicitly only when reading the figures
without the console.

## Configuration

See the [plugin README](../README.md) — the properties are shared by every source.

## The SPIs

```java
public interface RouteMetricsSource {
    Mono<RouteMetricsSnapshot> collect();     // per-route figures + the coverage they cover
}

public interface InstanceMetricsSource {
    Mono<InstanceMetricsSnapshot> collect();  // one row per running instance
}
```

Two contracts rather than one, because route figures are **merged** across instances — three
instances serving one route produce one number — while instance figures never are. The
average heap of a cluster is not a thing that exists.

A source that cannot answer returns an empty snapshot rather than failing: the views are
read-only pages, and no figure beats a broken gateway.

Declaring your own source ahead of `MetricsAutoConfiguration` makes it win over the local
one — this is exactly what the provider modules do:

```java
@AutoConfiguration(before = MetricsAutoConfiguration.class)
public class MyMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RouteMetricsSource.class)
    RouteMetricsSource myRouteMetricsSource() {
        return () -> Mono.just(new RouteMetricsSnapshot("my backend", myFigures()));
    }

}
```

## Merging

`RouteMetricsAggregator.merge` folds partial figures sharing a route id into one figure per
route. Every source needs it: the local one because the gateway publishes one timer per
status and method, the consolidating ones because each instance reports its own share.

Summing is not merging — an average of averages is wrong as soon as the parts carry
different weights, so the totals are rebuilt from the counts before latency and rates are
recomputed:

> one instance served 100 calls at 10 ms, another 300 at 50 ms → **40 ms**, not the 30 ms an
> average of averages would give.

## The local sources

`LocalRouteMetricsSource` reads the `spring.cloud.gateway.requests` timer, published per
route, method and status.

`LocalInstanceMetricsSource` reads the JVM and system meters Spring Boot binds
(`jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*`, `process.*`, `system.*`) plus the Reactor Netty
ones the gateway does not: `reactor.netty.connection.provider.*` for the pools and
`reactor.netty.eventloop.pending.tasks` for the event loops.

Both label their snapshot with the instance they came from, and both expose `read()`
returning the unmerged reading — which is what the Redis publisher and the discovery
endpoint hand out as is.

`InstanceMetric` carries raw counters and no derived ratios, so saturation and heap share are
computed by the view and cannot drift from the numbers they came from. A figure the JVM does
not publish is `-1`, never `0`: `jvm.memory.max` already answers `-1` for an unbounded pool,
and a zero file descriptor count would read as "no file open".

### Two Micrometer naming conventions

Micrometer ships two conventions for the JVM binders, which name memory and processor
figures differently:

| Figure | Micrometer convention | OpenTelemetry convention |
| --- | --- | --- |
| Heap / non-heap used | `jvm.memory.used` + `area=heap`\|`nonheap` | `jvm.memory.used` + `jvm.memory.type=heap`\|`non_heap` |
| Heap ceiling | `jvm.memory.max` + `area=heap` | `jvm.memory.limit` + `jvm.memory.type=heap` |
| Process CPU | `process.cpu.usage` | `jvm.cpu.recent_utilization` |
| Processors | `system.cpu.count` | `jvm.cpu.count` |

Everything else is named identically under both.

Spring Boot hands a `JvmMemoryMeterConventions` and a `JvmCpuMeterConventions` bean to
`JvmMemoryMetrics` and `ProcessorMetrics`, which is what decides the column above; with no
such bean the binders use the Micrometer ones. This plugin resolves the same two beans and
reads the meters through them, then falls back to the other convention when a figure is not
found: the beans describe the binders this application configures, and a registry can also be
filled by an OpenTelemetry agent or an OTLP bridge, which declare none. The second lookup only
runs when the first comes back empty.

```java
@Bean
JvmMemoryMeterConventions jvmMemoryMeterConventions() {
    return new OpenTelemetryJvmMemoryMeterConventions(Tags.empty());
}

@Bean
JvmCpuMeterConventions jvmCpuMeterConventions() {
    return new OpenTelemetryJvmCpuMeterConventions(Tags.empty());
}
```

## Instrumentation

`GatewayHttpClientInstrumentation` is an `HttpClientCustomizer` turning on the Reactor Netty
recorder the gateway never asks for. Without it those counters do not exist, however the
registry is queried.

`HttpClient` has no plain `metrics(boolean)` overload — the URI mapper is mandatory, or the
`uri` tag would create one meter per distinct downstream path. Every path is folded to a
single value, which keeps the `remote.address` tag: the distinction worth paying for.
