# spring-cloud-gateway-service-graph

Draws **who calls what**: one edge per caller, target service and route, weighted by the
number of calls and coloured by the number of failures, read through a source you choose.
Rendered by the service graph view of the [console](../spring-cloud-gateway-ui/README.md).

The gateway counts the calls that transit it. Where services reach each other *through* the
gateway, that is the service graph itself; calls going straight from one service to another
never reach it, and only a source reading a tracing backend can draw those. Every snapshot
carries the coverage it was computed over, so a partial picture never reads as the whole.

## Modules

| Module | What it does |
| --- | --- |
| [`-core`](spring-cloud-gateway-service-graph-core/README.md) | SPI, caller resolution, the counting filter and the local source. Always required. |
| [`-redis`](spring-cloud-gateway-service-graph-redis/README.md) | Each instance publishes its edges; the view reads them back |
| [`-prometheus`](spring-cloud-gateway-service-graph-prometheus/README.md) | Reads the graph this gateway publishes back from Prometheus |
| [`-tempo`](spring-cloud-gateway-service-graph-tempo/README.md) | Reads the graph Tempo derives from the spans it collected |

A provider declares its source ahead of the core, so it wins over the local one, and is
selected by `service-graph.provider`.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-core</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.service-graph`.

```yaml
spring.cloud.gateway.server.webflux.service-graph:
  provider: redis        # redis | prometheus | tempo; unset = this instance only
  instance-id: gateway-1 # defaults to HOSTNAME, then the host name
  excluded-routes:       # route ids never drawn; the whole id must match
    - openapi-docs-.*
  caller:
    claims: [azp, client_id]  # read in order, the first carrying a value wins
    header: X-Caller          # names the caller when no claim did
    header-first: false       # read the header before the claims (relay mode)
    max: 100                  # distinct callers before everything falls into _other_
```

| Property | Default | What it does |
| --- | --- | --- |
| `...service-graph.enabled` | `true` | Master switch |
| `...service-graph.provider` | — | `redis` \| `prometheus` \| `tempo`; unset reports this instance only |
| `...service-graph.instance-id` | `HOSTNAME`, then host name | Name this instance is reported under |
| `...service-graph.excluded-routes` | `[openapi-docs-.*]` | Route id patterns never counted |
| `...service-graph.caller.claims` | `[azp, client_id]` | Claims naming the caller, read in order |
| `...service-graph.caller.header` | — | Header naming the caller when no claim did |
| `...service-graph.caller.header-first` | `false` | Read the header before the claims |
| `...service-graph.caller.max` | `100` | Distinct caller names before everything falls into `_other_` |

## Choosing a source

The first three answer *what the gateway carried*; Tempo answers *what happened*, which is
not the same question.

| | Local (default) | Redis | Prometheus | Tempo |
| --- | --- | --- | --- | --- |
| Extra infrastructure | none | a Redis | a Prometheus | a tracing backend and its metrics-generator |
| Covers every instance | no | yes | yes | yes |
| Sees calls that avoided the gateway | no | no | no | **yes** |
| Survives a restart | no | no | **yes** | yes |
| Carries the route of an edge | yes | yes | yes | no |
| Cost per refresh | none | 1 scan | 1 query | 2 queries |
| Freshness | live | publish interval | scrape interval | generator interval |

**Local** is the graph of one JVM — right for a single instance, and a *biased* sample behind
a sticky load balancer: a given caller always lands on the same instance, so its edges are
complete there and missing everywhere else.

## Naming the caller

A caller becomes a tag on a counter, and a tag whose values are unbounded creates one time
series per value. So the caller is read from the claims naming the **client** a token was
issued to (`azp`, then `client_id`), never from the user or the address behind it, and the
number of distinct names is capped — everything past `caller.max` is counted under `_other_`.

This is a real limit, not an implementation detail: a graph of users, or of client addresses,
is answerable over a bounded window of audited events, not through a metric.

* **A service presenting its own token** — client credentials, or a token obtained by
  exchange — is named by `azp` and needs no further configuration.
* **A service relaying the token of the end user** is not: `azp` still names the client that
  started the chain, and an edge read from it would join two endpoints that never talked. The
  calling service has to name itself in a header, and `caller.header-first: true` tells the
  resolver to read it before the claims.

> **The header is chosen by whoever calls.** Turning `header-first` on makes the graph depend
> on a value an outside caller can forge. Strip it from the traffic entering the gateway from
> outside and set it again from the validated token — otherwise anyone can draw an edge
> between any two nodes.

## What is counted

One counter, `gateway.service.graph.calls`, incremented once per **routed** exchange:

| Tag | Value |
| --- | --- |
| `caller` | The client the call came from, or `unknown` / `_other_` |
| `service` | What the route targets: `lb://orders` is `orders`, otherwise the host, with the port when it is not the default of the scheme |
| `route` | The id of the route the call went through |
| `outcome` | `success`, `client-error`, `server-error` or `unknown` |

A request the gateway answered itself, or that matched no route, has no second endpoint to
draw and is not counted. A call that failed **is** counted before the failure is propagated —
an upstream that refused the connection is exactly the edge worth seeing.

A route matching `excluded-routes` is dropped where the calls are counted, not where the
graph is read, so it never becomes a series in the registry or in whatever scrapes it. The
default leaves out the documentation routes of the OpenAPI hub: fetching a contract is not one
service calling another.

The route is part of what makes an edge rather than a label on it, so two routes between the
same pair stay two edges.

## Sample

[gateway-full](../spring-cloud-gateway-samples/gateway/gateway-full/README.md) — port `8181`,
where `service-a` reaches `service-b` both through the gateway and directly.
