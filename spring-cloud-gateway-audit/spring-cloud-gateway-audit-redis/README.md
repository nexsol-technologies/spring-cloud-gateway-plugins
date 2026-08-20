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

The plugin declares no connection property of its own, `database` included — it would give
the same setting two places to disagree. That index is shared with everything else reusing
the connection (the metrics plugin, your cache, your sessions), and Redis Cluster only has
database 0, where the setting is ignored.

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
