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
    # Where this instance is reachable. Leave it unset: the address is guessed, and the
    # guess holds on a compose network, a pod network and a flat network alike. A literal
    # here would be published by every instance, which is worse than not setting it — see
    # below. Where the guess really is wrong, inject it per instance instead:
    #   instance-uri: ${GATEWAY_INSTANCE_URI:}
    instance-scheme: http
```

| Property | Default | What it does |
| --- | --- | --- |
| `...redis.key-prefix` | `gateway:metrics:` | Prefix of the route figures key each instance writes under |
| `...redis.instance-key-prefix` | `gateway:instances:` | Prefix of the instance figures key each instance writes under |
| `...redis.publish-interval` | `10s` | How often an instance publishes its figures |
| `...redis.time-to-live` | `45s` | How long a published key survives |
| `...redis.instance-uri` | — | Where this instance is reachable, published with its figures; guessed when unset |
| `...redis.instance-scheme` | `http` | Scheme the guessed address is built with; ignored when `instance-uri` is set |

> **The two prefixes must not nest.** The route source scans `key-prefix` with a wildcard, so
> an instance prefix placed under it — `gateway:metrics:instance:` — comes back in that scan
> and is discarded as unreadable, one warning per entry, on every refresh.

## The address an instance publishes

The figures carry the address the instance is reachable at. The
[console](../../spring-cloud-gateway-ui/README.md) reads that instance's Actuator endpoints
there — its loggers among them.

Unset, the address is built from the host this machine reports and the port the server bound,
which is the port in use rather than the port requested: on `server.port: 0` it is the one
taken. `instance-scheme` supplies its scheme.

Set, `instance-uri` is published as it is and `instance-scheme` is ignored.

**Leave it unset under Docker and Kubernetes.** A container is reachable from the other
containers of its network at the address it binds, and that address changes at every restart.

> **A literal here is published by every instance.** One value in an image, a ConfigMap or a
> shared `application.yml` makes the whole fleet report the same address: the console then lists
> one instance, and a logging level set on all instances reaches that one several times. Inject
> it per instance instead:
>
> ```yaml
> instance-uri: ${GATEWAY_INSTANCE_URI:}   # empty falls back to the built address
> ```
>
> from a per-container variable, or from the Kubernetes downward API (`status.podIP`).

An instance that publishes no address is listed by the console but cannot be read: its Actuator
endpoints are unreachable, and a level set across the fleet skips it.

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
