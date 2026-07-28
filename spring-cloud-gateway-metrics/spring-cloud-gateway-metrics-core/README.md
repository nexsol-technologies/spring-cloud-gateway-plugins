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

## Configuration

See the [plugin README](../README.md) for the properties, which are shared by every source.
