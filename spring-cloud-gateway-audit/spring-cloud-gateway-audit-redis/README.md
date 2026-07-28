# spring-cloud-gateway-audit-redis

Redis provider for the [auditing plugin](../README.md). Publishes each audit event,
rendered as a JSON object of its attributes, to a Redis **pub/sub** channel.

## Dependency

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-audit-redis</artifactId>
</dependency>
```

It brings `spring-boot-starter-data-redis-reactive`, so Spring Boot auto-configures a
`ReactiveStringRedisTemplate` from your `spring.data.redis.*` properties and the provider
reuses it.

## Configuration

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          audit:
            provider: redis
            redis:
              channel: gateway-audit   # default
  data:
    redis:
      host: localhost
      port: 6379
      database: 0                    # the logical database the channel is published on
      # username: default            # Redis ACL user (optional)
      # password: ${REDIS_PASSWORD}
      # ssl:
      #   enabled: true
```

The plugin declares no connection property of its own, `database` included: it would
duplicate `spring.data.redis.database` and give the same setting two places to disagree.
That index is shared with everything else reusing the connection — the metrics plugin, your
cache, your sessions. Note that Redis Cluster only has database 0, so `database` is ignored
in a clustered deployment.

| Property | Default | Description |
|----------|---------|-------------|
| `spring.cloud.gateway.server.webflux.audit.redis.channel` | `gateway-audit` | Pub/sub channel |

## Pub/sub mode

The provider calls `ReactiveStringRedisTemplate.convertAndSend(channel, payload)`, which
issues the Redis [`PUBLISH`](https://redis.io/docs/latest/commands/publish/) command. This
is genuine Redis pub/sub:

- Every subscriber currently listening on the channel receives the event.
- Delivery is fire-and-forget: events published while no subscriber is connected are **not**
  stored and cannot be replayed. If you need durability/replay, consume from a Redis
  Stream instead (provide your own `AuditEventPublisher` bean using `opsForStream()`).

### Subscribe from the CLI

```console
$ redis-cli SUBSCRIBE gateway-audit
1) "message"
2) "gateway-audit"
3) "{\"request.method\":\"GET\",\"request.path\":\"/patient/99098875/alert-summaries\",\"response.status\":\"OK\"}"
```

### Subscribe from a Spring application

```java
@Bean
ApplicationRunner auditSubscriber(ReactiveStringRedisTemplate redis) {
    return args -> redis.listenToChannel("gateway-audit")
        .map(ReactiveSubscription.Message::getMessage)
        .subscribe(payload -> log.info("audit {}", payload));
}
```

`listenToChannel` returns a `Flux` of messages; keep the subscription alive for the
lifetime of the application (for example by subscribing in an `ApplicationRunner` or a
`@PostConstruct`).

## Payload

The published message is the JSON object of the event attributes, for example:

```json
{"request.method":"GET","request.path":"/patient/99098875/alert-summaries","response.status":"OK","jwt.user.id":"toto"}
```
