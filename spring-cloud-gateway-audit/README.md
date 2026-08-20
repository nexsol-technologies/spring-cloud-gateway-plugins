# spring-cloud-gateway-audit

Captures request, response and identity attributes for each exchange the gateway serves and
pushes them to a pluggable provider. Auditing runs per route (the `Audit` gateway filter) or
globally (a web filter).

## Modules

| Module | What it does |
| --- | --- |
| [`-core`](spring-cloud-gateway-audit-core/README.md) | SPI, attribute collector, per-route filter and global web filter, default publisher. Always required. |
| [`-kafka`](spring-cloud-gateway-audit-kafka/README.md) | Publishes to a Kafka topic (SASL/SSL supported) |
| [`-redis`](spring-cloud-gateway-audit-redis/README.md) | Publishes to a Redis pub/sub channel |
| [`-r2dbc`](spring-cloud-gateway-audit-r2dbc/README.md) | Inserts into a relational table through R2DBC |

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-audit-core</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

On its own, `-core` uses the built-in default publisher: it logs the event and republishes it
as a Spring `ApplicationEvent`. To push to a backend, add one provider module and select it:

```yaml
spring.cloud.gateway.server.webflux.audit:
  provider: kafka   # kafka | redis | r2dbc; unset = the default log/event publisher
```

A provider reuses the application's existing `KafkaTemplate` /
`ReactiveStringRedisTemplate` / `DatabaseClient`. If the selected provider's template is
missing, auditing falls back to the core default publisher.

## Configuration

See [`-core`](spring-cloud-gateway-audit-core/README.md#configuration) for the audited
attributes, the logical groups, the masking of secrets in the query string and where
auditing runs.

## Sample

[gateway-audit](../spring-cloud-gateway-samples/gateway/gateway-audit/README.md) — port
`8205`, with the three providers behind a profile each.
