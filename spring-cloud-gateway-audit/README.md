# spring-cloud-gateway-audit

Auditing plugin for Spring Cloud Gateway. It captures request/response and identity
attributes for each exchange and pushes them to a pluggable provider.

## Modules

| Module | Description |
|--------|-------------|
| [spring-cloud-gateway-audit-core](spring-cloud-gateway-audit-core/README.md)  | SPI, attribute collector, per-route filter and global web filter, default publisher |
| [spring-cloud-gateway-audit-kafka](spring-cloud-gateway-audit-kafka/README.md) | Publishes audit events to a Kafka topic (with SASL/SSL support) |
| [spring-cloud-gateway-audit-redis](spring-cloud-gateway-audit-redis/README.md) | Publishes audit events to a Redis pub/sub channel |
| [spring-cloud-gateway-audit-r2dbc](spring-cloud-gateway-audit-r2dbc/README.md) | Inserts audit events into a relational table through R2DBC |

## How it fits together

Add [`-core`](spring-cloud-gateway-audit-core/README.md) to collect and audit attributes.
On its own it uses the built-in default publisher (logs the event and republishes it as a
Spring `ApplicationEvent`).

To push to a backend, add one provider module and select it:

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          audit:
            provider: kafka   # kafka | redis | r2dbc ; unset = default log/event publisher
```

The provider reuses the application's existing `KafkaTemplate` /
`ReactiveStringRedisTemplate` / `DatabaseClient`. If the selected provider's template is
missing, auditing falls back to the core default publisher.

See [spring-cloud-gateway-audit-core](spring-cloud-gateway-audit-core/README.md) for the
audited attributes, the logical groups and where auditing runs (per-route filter or global
web filter).
