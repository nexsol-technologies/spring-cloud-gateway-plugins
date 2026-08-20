# spring-cloud-gateway-service-graph-core

The contract every source answers, the filter that counts the calls, the caller resolution
and the source reading what this instance counted.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-core</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

See the [plugin README](../README.md) — the properties are shared by every source.

## The contract

```java
public interface ServiceGraphSource {
    Mono<ServiceGraphSnapshot> collect();
}
```

A snapshot carries the `coverage` — what the graph covers, in words meant for a human — the
`nodes` and the `edges`. Nodes are derived from the edges rather than declared, so a source
only ever has to produce edges:

```java
ServiceGraphSnapshot.of("3 instances, via Redis", edges);
```

`of` sums everything joining the same two endpoints through the same route, orders by call
count and derives the nodes. Every source needs it: the local one because calls are counted
per outcome, a consolidating one because each instance reports the share it served.

A source that cannot answer returns `ServiceGraphSnapshot.empty("…")` rather than failing —
the graph is a read-only page, and no graph beats a broken gateway.

`GraphEdge` carries `calls` and `errors`, never a rate: a rate cannot be merged, and dividing
before summing is how an average of averages goes wrong.

The node kind is derived, never declared — an endpoint the gateway routed to is a `SERVICE`,
one that only ever called is a `CALLER`. An endpoint doing both is one `SERVICE` node
carrying the calls of both its sides, not two half-nodes.

## Counting

`ServiceGraphFilter` is a `GlobalFilter` at `HIGHEST_PRECEDENCE`, so the count is taken
around the whole chain. A `GlobalFilter` and not a `WebFilter` on purpose: an edge needs a
route at its far end, and a request the gateway answered itself has none.

The far end is named after what the route targets. `targetService` reads the URI, so
`lb://orders` is `orders` and `http://orders` is `orders` too — the gateway normalises a
declared URI to an explicit default port, and a node named `orders:80` would be the same
service under a second name.

Losing an edge never costs a response: a failure while counting is logged at debug and
swallowed, and an application publishing no metrics has no `MeterRegistry`, so the filter
simply passes the exchange through.

`RouteExclusions` is applied here and only here — excluding at read time would leave the
series behind. A route with no id is excluded by the same check, since an edge has to name
what it reached.

## Naming the caller

`CallerResolver` reads the first configured claim carrying a value, then the configured
header, then reports `unknown` — or the header first when `caller.header-first` is on. The
result is capped to `caller.max` distinct names, everything past it counted under `_other_`.

The check and the insertion behind that cap are not atomic, so a burst of new callers can
name a handful more than the maximum. Deliberate: the guard bounds the number of series
rather than being exact, and locking the data path of every request would cost more than the
few extra names.

## The local source

`LocalServiceGraphSource` reads the counters of the running instance and reports them under
`this instance only (<instance-id>)`. Its `read()` is public, so a provider consolidating
several instances reuses the local reading as its own contribution.
