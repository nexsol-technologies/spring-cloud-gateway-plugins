# spring-cloud-gateway-metrics-redis

Each instance publishes what it counted into Redis; the traffic view sums whatever is
there. No instance ever calls another.

## Dependency

```xml
    <dependency>
       <groupId>ch.nexsol-tech.gateway</groupId>
       <artifactId>spring-cloud-gateway-metrics-redis</artifactId>
       <version>${spring-cloud-gateway-plugins.version}</version>
    </dependency>
```

## Configuration

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          metrics:
            provider: redis
            redis:
              key-prefix: "gateway:metrics:"
              instance-key-prefix: "gateway:instances:"
              publish-interval: 10s
              time-to-live: 45s
```

| Property | Default | Description |
|----------|---------|-------------|
| `key-prefix` | `gateway:metrics:` | Prefix of the route figures key each instance writes under |
| `instance-key-prefix` | `gateway:instances:` | Prefix of the technical figures key each instance writes under |
| `publish-interval` | `10s` | How often an instance publishes its figures |
| `time-to-live` | `45s` | How long a published key survives |

**The two prefixes must not nest.** The route source scans `key-prefix` with a wildcard, so
an instance prefix placed under it — `gateway:metrics:instance:` — would come back in that
scan and be discarded as unreadable, one warning per entry, on every single refresh. Hence
a namespace of its own rather than the more obvious suffix.

## The connection

It brings `spring-boot-starter-data-redis-reactive`, so Spring Boot auto-configures a
`ReactiveStringRedisTemplate` from your `spring.data.redis.*` properties and this provider
reuses it:

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0                    # the logical database the keys land in
      # username: default            # Redis ACL user (optional)
      # password: ${REDIS_PASSWORD}
      # ssl:
      #   enabled: true
```

The plugin declares no connection property of its own, `database` included: it would
duplicate `spring.data.redis.database` and give the same setting two places to disagree.

Two consequences:

- **The database index is shared.** Everything reusing that connection — the audit plugin,
  your cache, your sessions — lives in the same logical database. The keys still do not
  collide, since this provider only ever reads and writes its own `key-prefix`, but you
  cannot put the metrics in one database and the rest elsewhere.
- **Redis Cluster only has database 0.** Setting `database` there is ignored, so do not
  count on it to separate anything in a clustered deployment.

## How it works

Each instance writes **its own key** (`<prefix><instance-id>`) and never touches the
others'. That is what lets every instance write concurrently without any locking. Two keys
per instance, one per family of figures.

The route figures are summed on read; the technical ones are not. One instance is one row,
so they are concatenated: a gateway with the combined heap of three instances is not a
thing that exists.

Keys carry a time to live, so an instance that stops publishing fades out of the figures on
its own — a replaced pod stops being counted without anyone cleaning up after it.

**`time-to-live` must comfortably outlive `publish-interval`.** Set it too close and an
instance disappears from the figures between two of its own writes, making the totals dip
for no reason.

Reading uses `SCAN`, not `KEYS`, so a large keyspace is not blocked while the view
refreshes.

## What it costs

- The figures lag by up to one publish interval. That is the trade for never calling
  another instance: an instance that is busy, or behind a closed network, still counts
  through what it last published.
- Counters live in memory, so an instance that restarts loses what it had counted. Only the
  Prometheus source keeps history across restarts.

An entry that cannot be read — written by an older version, or by something else — is
skipped rather than costing the figures of every other instance. If Redis is unreachable the
view reports no data and says so.
