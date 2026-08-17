# spring-cloud-gateway-service-graph-core

The contract every source answers, the filter that counts the calls, and the source reading
what this instance counted.

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-core</artifactId>
</dependency>
```

## The contract

```java
public interface ServiceGraphSource {
    Mono<ServiceGraphSnapshot> collect();
}
```

A `ServiceGraphSnapshot` carries three things: the `coverage` — what the graph covers, in
words meant for a human — the `nodes`, and the `edges` between them. Nodes are derived from
the edges rather than declared, so a source only ever has to produce edges:

```java
ServiceGraphSnapshot.of("3 instances, via Redis", edges);
```

`of` sums everything joining the same two endpoints through the same route, orders by call
count and derives the nodes. Every source needs it: the local one because the calls are
counted per outcome, a consolidating one because each instance reports the share it served.
A source that cannot answer returns `ServiceGraphSnapshot.empty("...")` rather than failing
— the graph is a read-only page, and no graph is better than a broken gateway.

`GraphEdge` carries `calls` and `errors`, never a rate: a rate cannot be merged, and
dividing before summing is how an average of averages goes wrong.

The node kind is derived, never declared: an endpoint the gateway routed to is a `SERVICE`,
one that only ever called is a `CALLER`. An endpoint that does both — a service reaching
another one through the gateway — is one `SERVICE` node carrying the calls of both its
sides, not two half-nodes.

## Counting

`ServiceGraphFilter` is a `GlobalFilter` at `HIGHEST_PRECEDENCE`, so the count is taken
around the whole chain. It is a `GlobalFilter` and not a `WebFilter` on purpose: an edge
needs a route at its far end, and a request the gateway answered itself has none.

The far end is named after what the route targets — `targetService` reads the URI, so
`lb://orders` is `orders` and `http://orders` is `orders` too: the gateway normalises a
declared URI to an explicit default port when it builds the route, and a node named
`orders:80` would be the same service under a second name.

Losing an edge never costs a response — a failure while counting is logged at debug and
swallowed. An application that publishes no metrics has no `MeterRegistry`, and then nothing
is counted at all: the filter passes the exchange straight through.

`RouteExclusions` is applied here, and only here. Excluding at read time would leave the
series behind; excluding at count time means an excluded route costs nothing anywhere. A
route with no id is excluded by the same check — an edge has to name what it reached.

## Naming the caller

`CallerResolver` reads the first configured claim carrying a value, then the configured
header, then reports `unknown` — or the header first when `caller.header-first` is on,
which is what a gateway whose services relay the end user's token needs. The result is
capped to `caller.max` distinct names, and everything past it is counted under `_other_`.

The check and the insertion behind that cap are not atomic, so a burst of new callers can
name a handful more than the maximum. That is deliberate: the guard exists to bound the
number of series, not to be exact, and locking the data path of every request to make it
exact would cost more than the few extra names.

## The local source

`LocalServiceGraphSource` reads the counters of the running instance and reports them under
`this instance only (<instance-id>)`. Its `read()` is public so a provider consolidating
several instances can reuse the local reading as its own contribution.
