# spring-cloud-gateway-service-graph

Draws **who calls what**: one edge per caller, target service and route, weighted by the
number of calls and coloured by the number of failures, read through a source you choose.

## What it can and cannot see

The gateway counts the calls that transit it. What that gives depends on your topology:

* **A service reaches another one through the gateway** — the gateway is on every edge, and
  what it counts *is* the service graph. `frontend → service-a` and `service-a → service-b`
  are two exchanges it served, and both are in its own counters.
* **Services also talk to each other directly** — those calls never reach the gateway, and
  no amount of counting will show them. A source reading a tracing backend will.

Either way, every snapshot carries the coverage it was computed over, so a partial picture
never reads as the whole.

The default source reads the counters of the running instance. Behind a load balancer that
is the share of the traffic that instance served, and a sticky balancer makes it a *biased*
share: a given caller always lands on the same instance, so its edges are complete there
and missing everywhere else. That is why the provider modules exist.

## Modules

| Module | Description |
|--------|-------------|
| [spring-cloud-gateway-service-graph-core](spring-cloud-gateway-service-graph-core/README.md) | SPI, caller resolution, the counting filter and the local source |
| [spring-cloud-gateway-service-graph-redis](spring-cloud-gateway-service-graph-redis/README.md) | Each instance publishes its edges; the view reads them back |
| [spring-cloud-gateway-service-graph-prometheus](spring-cloud-gateway-service-graph-prometheus/README.md) | Reads the graph this gateway publishes back from Prometheus |
| [spring-cloud-gateway-service-graph-tempo](spring-cloud-gateway-service-graph-tempo/README.md) | Reads the graph Tempo derives from the spans it collected |

Add `-core` for the graph of one instance; a provider on top to consolidate. A provider
declares its source ahead of the core, so it wins over the local one, and is selected by
`service-graph.provider`.

## Choosing a source

The first three answer *what the gateway carried*; the last answers *what happened*, which
is not the same question.

| | Local (default) | Redis | Prometheus | Tempo |
|---|---|---|---|---|
| Extra infrastructure | none | a Redis | a Prometheus | a tracing backend and its metrics-generator |
| Covers every instance | no | yes | yes | yes |
| Sees calls that avoided the gateway | no | no | no | **yes** |
| Survives a restart | no | no | **yes** | yes |
| Carries the route of an edge | yes | yes | yes | no |
| Cost per refresh | none | 1 scan | 1 query | 2 queries |
| Freshness | live | publish interval | scrape interval | generator interval |

**Local** is the graph of one JVM: right for a single instance, a biased sample behind a
sticky load balancer.

**Redis** consolidates live counters — each instance writes its own key with a time to
live, so a replaced pod fades out on its own. The figures start again from zero when every
instance is replaced.

**Prometheus** is the only one of the three that keeps history: the series outlive the JVM
that produced them, so a rolling restart does not reset the edges.

**Tempo** is the only source that sees an edge the gateway never carried. Where every call
between services transits the gateway, it mostly confirms what the other three already
know; where services also talk to each other directly, it is the only one that can draw
those edges — at the price of a tracing backend, and of an accuracy that is the accuracy of
your instrumentation. Its edges carry no route, because most of them never went through
one.

## Naming the caller

A caller becomes a tag on a counter, and a tag whose values are unbounded creates one time
series per value — in this instance's registry, and in every backend that scrapes it. So
the caller is read from the claims naming the *client* a token was issued to (`azp`, then
`client_id`), never from the user or the address behind it, and the number of distinct
names is capped: everything past the cap is counted under a single `_other_` node.

This is a real limit, not a detail of the implementation. A graph of users, or of client
addresses, is answerable over a bounded window of audited events — it is not answerable
through a metric, and this plugin is built on a metric.

**When a service presents its own token** — client credentials, or a token obtained by
exchange — `azp` names it and the edges are right with no further configuration.

**When a service relays the token of the end user**, `azp` still names the client that
started the chain, and an edge read from it would join two endpoints that never talked to
each other. The calling service has to name itself in a header, and `caller.header-first`
tells the resolver to read it before the claims:

```yaml
spring.cloud.gateway.server.webflux.service-graph.caller:
  header: X-Caller
  header-first: true
```

> **The header is chosen by whoever calls.** Turning `header-first` on makes the graph
> depend on a value an outside caller can forge. It has to be stripped from the traffic
> entering the gateway from outside and set again from the validated token — otherwise
> anyone can draw an edge between any two nodes.

## Configuration

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          service-graph:
            enabled: true          # the plugin is on by default
            instance-id: gateway-1 # defaults to HOSTNAME, then the host name
            excluded-routes:       # route ids never drawn; the whole id must match
              - openapi-docs-.*
            caller:
              claims: [azp, client_id]  # read in order, first one carrying a value wins
              header:                   # names the caller when no claim did; unset by default
              header-first: false       # read the header before the claims (relay mode)
              max: 100                  # distinct callers before everything falls into _other_
```

## What is counted

One counter, `gateway.service.graph.calls`, tagged:

| Tag | Value |
|---|---|
| `caller` | the client the call came from, or `unknown` / `_other_` |
| `service` | what the route targets: `lb://orders` is `orders`, otherwise the host, with the port when it is not the default of the scheme |
| `route` | the id of the route the call went through |
| `outcome` | `success`, `client-error`, `server-error` or `unknown` |

It is incremented once per **routed** exchange: a request the gateway answered itself, or
that matched no route, has no second endpoint to draw and is not counted. A call that
failed is counted before the failure is propagated — an upstream that refused the
connection is exactly the edge worth seeing.

A route matching `excluded-routes` is not counted at all — dropped where the calls are
counted rather than where the graph is read, so it never becomes a series in the registry
or in whatever scrapes it. The one default, `openapi-docs-.*`, leaves out the documentation
routes the OpenAPI hub publishes: fetching a contract is not one service calling another,
and drawing it would put the console among the callers of every service it documents.

The route is part of what makes an edge rather than a label on it, so two routes between
the same pair stay two edges: which one carries the traffic is a question the graph should
be able to answer.
