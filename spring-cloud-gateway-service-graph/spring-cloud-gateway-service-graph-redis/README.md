# spring-cloud-gateway-service-graph-redis

Each instance publishes the edges it counted into Redis; the view sums them.

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-service-graph-redis</artifactId>
</dependency>
```

```yaml
spring:
  data.redis:
    host: redis
  cloud.gateway.server.webflux:
    service-graph:
      provider: redis
      redis:
        key-prefix: "gateway:service-graph:"   # the instance id is appended
        publish-interval: 10s
        time-to-live: 45s
```

## How it consolidates

Each instance writes **its own key** — `<key-prefix><instance-id>` — and never touches the
others'. That is what lets several writers publish at once with no locking at all. The key
carries a time to live, so an instance that stops publishing fades out of the graph on its
own: this is how a replaced pod stops being drawn, without anything having to notice it
left.

The time to live must outlive the publish interval, otherwise an instance disappears
between two of its own writes. The defaults leave a factor of four.

Reading is a `SCAN` — not `KEYS`, which would block a large keyspace on every refresh —
followed by a `GET` per key, and the edges are summed by `ServiceGraphSnapshot.of`. Nothing
has to be reachable: an instance that is busy, or behind a closed network, still counts
through what it last published. The graph lags by at most one publish interval, which is
the trade for not calling anyone.

A key written by an older version, or by something else entirely, is dropped with a warning
rather than costing the edges of every other instance.

## What it reports

The coverage names how many instances answered — `3 instances, via Redis` — or says that
none has published yet, so an empty graph is never mistaken for a quiet gateway. When Redis
cannot be reached the source reports `Redis unavailable` instead of failing: the graph is a
read-only page.

The instance id comes from `InstanceIdentity`: the configured `service-graph.instance-id`,
then `HOSTNAME`, then the host name.
