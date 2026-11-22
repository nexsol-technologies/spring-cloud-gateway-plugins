# spring-cloud-gateway-audit-redis

Redis provider for the [auditing plugin](../README.md). Publishes each audit event, rendered
as a JSON object of its attributes, to a Redis **pub/sub** channel.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-audit-redis</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

It brings `spring-boot-starter-data-redis-reactive`, so Spring Boot auto-configures a
`ReactiveStringRedisTemplate` from the `spring.data.redis.*` properties and the provider
reuses it.

## Configuration

```yaml
spring.cloud.gateway.server.webflux.audit:
  provider: redis
  redis:
    channel: gateway-audit
    max-pending: 10000           # events held while Redis does not answer; past it they are dropped
    send-timeout: 30s            # how long one publication may take

spring.data.redis:
  host: localhost
  port: 6379
  database: 0                    # the logical database the channel is published on
  # username: default            # Redis ACL user
  # password: ${REDIS_PASSWORD}
  # ssl.enabled: true
```

| Property | Default | What it does |
| --- | --- | --- |
| `...audit.redis.channel` | `gateway-audit` | Pub/sub channel |
| `...audit.redis.max-pending` | `10000` | How many events may be pending at once; past it an event is dropped and reported. `0` sets no limit |
| `...audit.redis.send-timeout` | `30s` | How long one publication may take before it is given up and counted as failed. `0` sets no timeout |

The plugin declares no connection property of its own, `database` included — it would give
the same setting two places to disagree. That index is shared with everything else reusing
the connection (the metrics plugin, your cache, your sessions), and Redis Cluster only has
database 0, where the setting is ignored.

## When the Redis does not keep up

Publishing never waits, so a Redis slower than the traffic does not slow a request down:
the events pile up instead, each holding its payload. `max-pending` bounds them. Past it an
event is **dropped**, and the gateway says so in the log — one `WARN` at most every 30
seconds, carrying how many events were dropped and how many the Redis did not take since
startup:

```
audit events are being lost towards the redis channel gateway-audit: 124 dropped over the limit of 10000 pending, 3 failed (totals since startup, reported at most every 30 s)
```

The Redis client queues every command while it is disconnected, without limit; this bound is
what keeps an outage from growing the heap by one event per request. `send-timeout` gives the
place of an event back when Redis never answers, so the events that follow are not dropped
for good.

## Pub/sub, not a queue

The provider calls `convertAndSend(channel, payload)`, which issues the Redis
[`PUBLISH`](https://redis.io/docs/latest/commands/publish/) command:

* Every subscriber currently listening on the channel receives the event.
* Delivery is fire-and-forget — events published while no subscriber is connected are **not**
  stored and cannot be replayed. For durability, consume from a Redis Stream instead by
  declaring your own `AuditEventPublisher` bean using `opsForStream()`.

Subscribing from the CLI:

```console
$ redis-cli SUBSCRIBE gateway-audit
1) "message"
2) "gateway-audit"
3) "{\"request.method\":\"GET\",\"request.path\":\"/book/99098875/reviews\",\"response.status\":\"OK\"}"
```

Subscribing from a Spring application — `listenToChannel` returns a `Flux`, so keep the
subscription alive for the lifetime of the application:

```java
@Bean
ApplicationRunner auditSubscriber(ReactiveStringRedisTemplate redis) {
    return args -> redis.listenToChannel("gateway-audit")
        .map(ReactiveSubscription.Message::getMessage)
        .subscribe(payload -> log.info("audit {}", payload));
}
```

## Payload

The published message is the JSON object of the event attributes:

```json
{"request.method":"GET","request.path":"/book/99098875/reviews","response.status":"OK","jwt.user.id":"toto"}
```

## Sample

[gateway-audit](../../spring-cloud-gateway-samples/gateway/gateway-audit/README.md),
`redis` profile — port `8205`.
