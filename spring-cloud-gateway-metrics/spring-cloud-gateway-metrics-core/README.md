# spring-cloud-gateway-metrics-core

The SPI the traffic view reads through, and the source that reads the meter registry of the
running instance.

## Dependency

```xml
    <dependency>
       <groupId>ch.nexsol-tech.gateway</groupId>
       <artifactId>spring-cloud-gateway-metrics-core</artifactId>
       <version>${spring-cloud-gateway-plugins.version}</version>
    </dependency>
```

The gateway UI already depends on it; add it explicitly only when reading the figures
without the UI.

## The SPI

```java
public interface RouteMetricsSource {
    Mono<RouteMetricsSnapshot> collect();
}
```

A snapshot is the per-route figures **plus the coverage they were computed over**. The
coverage is carried with the figures because it is the only thing telling a reader whether
a number is the whole gateway or one instance of it.

Returning a `Mono` is not ceremony: every source but the local one performs I/O. A source
that cannot answer reports an empty snapshot rather than failing — the traffic view is a
read-only page, and no figure beats a broken gateway.

Declare your own source ahead of `MetricsAutoConfiguration` and it wins over the local one:

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

## The second SPI

```java
public interface InstanceMetricsSource {
    Mono<InstanceMetricsSnapshot> collect();
}
```

Deliberately a separate contract rather than a specialisation of the first, because the two
differ on the one thing that matters: route figures are **merged** across instances — three
instances serving one route produce one number — while instance figures never are. Each
instance is a row of its own, and the average heap of a cluster is not a thing that exists.
Behind a shared abstraction, merging would have to become configurable.

`InstanceMetric` therefore carries raw counters and no derived ratios: the saturation and
the heap share are computed by the view, so they cannot drift from the numbers they came
from. A figure the JVM does not publish is `-1`, never `0` — `jvm.memory.max` already
answers `-1` for an unbounded pool, and the file descriptor counters do not exist outside
Unix, where a zero would read as "no file open".

It also carries which instrumentation the instance runs with, per instance rather than once
per snapshot: nothing guarantees every instance was configured alike, and the one that was
not is exactly what the view should reveal.

## Merging

`RouteMetricsAggregator.merge` folds partial figures sharing a route id into one figure per
route. Every source needs it: the local one because the gateway publishes one timer per
status and method, the consolidating ones because each instance reports its own share.

Summing is not merging. An average of averages is wrong as soon as the parts carry
different weights, so the totals are rebuilt from the counts before the latency and the
rates are recomputed:

> one instance served 100 calls at 10 ms, another 300 at 50 ms → **40 ms**, not the 30 ms an
> average of averages would give.

## The local source

Reads the `spring.cloud.gateway.requests` timer, which the gateway publishes per route,
method and status. It labels its snapshot with the instance it came from
(`this instance only (gateway-7f9c4)`), because behind a load balancer that figure is a
share of the traffic, not the traffic.

`read()` returns the unmerged partial figures, so a provider consolidating several
instances can reuse the local reading as its own contribution.

When no meter registry is present the source reports no data instead of failing.

## The local instance source

`LocalInstanceMetricsSource` reads the JVM and system meters Spring Boot binds out of the
box (`jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*`, `process.*`, `system.*`) and the Reactor
Netty ones the gateway does not: `reactor.netty.connection.provider.*` for the pools and
`reactor.netty.eventloop.pending.tasks` for the event loops.

Micrometer 1.16 ships two naming conventions for the JVM binders, and the memory and
processor figures are read under both:

| Figure | Micrometer convention | OpenTelemetry convention |
| --- | --- | --- |
| Heap / non-heap used | `jvm.memory.used` + `area=heap`\|`nonheap` | `jvm.memory.used` + `jvm.memory.type=heap`\|`non_heap` |
| Heap ceiling | `jvm.memory.max` + `area=heap` | `jvm.memory.limit` + `jvm.memory.type=heap` |
| Process CPU | `process.cpu.usage` | `jvm.cpu.recent_utilization` |
| Processors | `system.cpu.count` | `jvm.cpu.count` |

Everything else read here is named identically under both. Spring Boot always builds the
binders with the historical convention and exposes no property to switch, so an application
only lands on the OpenTelemetry names by declaring its own `JvmMemoryMetrics` or
`ProcessorMetrics` bean — which cannot be detected from the outside, the conventions being
held in a private field. The registry itself is therefore asked: the historical name first,
the OpenTelemetry one only when that search comes back empty. Under the historical
convention nothing changes and nothing extra is looked up.

The pool gauges are folded per connection provider and downstream address. Reactor Netty
also tags them with an `id` identifying a pool instance, whose cardinality follows the
internals of the transport; what an operator reads is "the pool towards service-a is full",
so the instances behind that are summed.

`read()` returns the single row, for the same reason as above — the discovery endpoint and
the Redis publisher both hand it out as is.

## Instrumentation

`GatewayHttpClientInstrumentation` is an `HttpClientCustomizer` turning on the Reactor Netty
recorder the gateway never asks for. Without it those counters do not exist, however the
registry is queried — `HttpClientFactory` never calls `metrics(...)`, and no gateway
property exposes it.

`HttpClient` has no plain `metrics(boolean)` overload: the URI mapper is mandatory, because
the `uri` tag would otherwise carry the downstream path and create one meter per distinct
path of every service behind the gateway. Every path is folded to a single value, which
keeps the `remote.address` tag — the distinction worth paying for.

## Configuration

See the [plugin README](../README.md) for the properties, which are shared by every source.
