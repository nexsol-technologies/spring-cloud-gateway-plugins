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
              publish-interval: 10s
              time-to-live: 45s
```

| Property | Default | Description |
|----------|---------|-------------|
| `key-prefix` | `gateway:metrics:` | Prefix of the key each instance writes under |
| `publish-interval` | `10s` | How often an instance publishes its figures |
| `time-to-live` | `45s` | How long a published key survives |

The connection itself comes from the usual `spring.data.redis.*` properties.

## How it works

Each instance writes **its own key** (`<prefix><instance-id>`) and never touches the
others'. That is what lets every instance write concurrently without any locking.

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
