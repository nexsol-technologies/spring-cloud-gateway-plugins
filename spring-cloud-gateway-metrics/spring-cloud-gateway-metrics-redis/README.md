# spring-cloud-gateway-metrics-redis

Each instance publishes what it counted into Redis; the views read whatever is there. No
instance ever calls another.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-metrics-redis</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

It brings `spring-boot-starter-data-redis-reactive`, so Spring Boot auto-configures a
`ReactiveStringRedisTemplate` from the `spring.data.redis.*` properties and this provider
reuses it. The plugin declares no connection property of its own — `database` included, which
would give the same setting two places to disagree.

```yaml
spring.data.redis:
  host: localhost
  port: 6379
  database: 0                    # the logical database the keys land in
  # username: default            # Redis ACL user
  # password: ${REDIS_PASSWORD}
  # ssl.enabled: true
```

Two consequences: the database index is **shared** with everything else reusing that
connection (the keys never collide, but metrics cannot live in a database of their own), and
Redis Cluster only has database 0, where the setting is ignored.

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.metrics`.

```yaml
spring.cloud.gateway.server.webflux.metrics:
  provider: redis
  redis:
    key-prefix: "gateway:metrics:"
    instance-key-prefix: "gateway:instances:"
    publish-interval: 10s
    time-to-live: 45s
```

| Property | Default | What it does |
| --- | --- | --- |
| `...redis.key-prefix` | `gateway:metrics:` | Prefix of the route figures key each instance writes under |
| `...redis.instance-key-prefix` | `gateway:instances:` | Prefix of the instance figures key each instance writes under |
| `...redis.publish-interval` | `10s` | How often an instance publishes its figures |
| `...redis.time-to-live` | `45s` | How long a published key survives |

> **The two prefixes must not nest.** The route source scans `key-prefix` with a wildcard, so
> an instance prefix placed under it — `gateway:metrics:instance:` — comes back in that scan
> and is discarded as unreadable, one warning per entry, on every refresh.

> **`time-to-live` must comfortably outlive `publish-interval`.** Set too close, an instance
> disappears from the figures between two of its own writes and the totals dip for no reason.

## How it works

Each instance writes **its own keys** (`<prefix><instance-id>`, one per family) and never
touches the others'. That is what lets every instance write concurrently without any locking.

Route figures are summed on read; instance figures are concatenated — one instance is one
row. Keys carry a time to live, so an instance that stops publishing fades out on its own and
a replaced pod stops being counted without anyone cleaning up after it. Reading uses `SCAN`,
not `KEYS`, so a large keyspace is not blocked while a view refreshes.

## What it costs

* The figures lag by up to one publish interval — the trade for never calling another
  instance: a busy or unreachable instance still counts through what it last published.
* Counters live in memory, so an instance that restarts loses what it had counted. Only the
  [Prometheus source](../spring-cloud-gateway-metrics-prometheus/README.md) keeps history.

An entry that cannot be read is skipped rather than costing the figures of every other
instance. If Redis is unreachable, the views report no data and say so.

## Sample

[gateway-metrics](../../spring-cloud-gateway-samples/gateway/gateway-metrics/README.md),
`redis` profile — port `8206`.
