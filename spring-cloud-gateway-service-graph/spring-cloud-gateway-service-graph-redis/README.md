# spring-cloud-gateway-service-graph-redis

Each instance publishes the edges it counted into Redis; the view sums them. No instance ever
calls another.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-redis</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.service-graph`.

```yaml
spring.data.redis.host: redis

spring.cloud.gateway.server.webflux.service-graph:
  provider: redis
  redis:
    key-prefix: "gateway:service-graph:"   # the instance id is appended
    publish-interval: 10s
    time-to-live: 45s
```

| Property | Default | What it does |
| --- | --- | --- |
| `...service-graph.redis.key-prefix` | `gateway:service-graph:` | Prefix of the key each instance writes under; the instance id is appended |
| `...service-graph.redis.publish-interval` | `10s` | How often an instance publishes its edges |
| `...service-graph.redis.time-to-live` | `45s` | How long a published key survives |

> **`time-to-live` must outlive `publish-interval`**, or an instance disappears from the graph
> between two of its own writes. The defaults leave a factor of four.

## How it consolidates

Each instance writes **its own key** (`<key-prefix><instance-id>`) and never touches the
others', which is what lets several writers publish at once with no locking. The key carries
a time to live, so an instance that stops publishing fades out on its own — a replaced pod
stops being drawn without anything having to notice it left.

Reading is a `SCAN`, not `KEYS` — which would block a large keyspace on every refresh —
followed by a `GET` per key, and the edges are summed by `ServiceGraphSnapshot.of`. Nothing
has to be reachable: an instance that is busy, or behind a closed network, still counts
through what it last published. The graph lags by at most one publish interval, which is the
trade for not calling anyone.

A key written by an older version, or by something else, is dropped with a warning rather
than costing the edges of every other instance.

The instance id comes from `InstanceIdentity`: the configured `service-graph.instance-id`,
then `HOSTNAME`, then the host name.

## What it reports

The coverage names how many instances answered — `3 instances, via Redis` — or says that none
has published yet, so an empty graph is never mistaken for a quiet gateway. When Redis cannot
be reached the source reports `Redis unavailable` rather than failing.

## Sample

[gateway-full](../../spring-cloud-gateway-samples/gateway/gateway-full/README.md) — port
`8181`.
