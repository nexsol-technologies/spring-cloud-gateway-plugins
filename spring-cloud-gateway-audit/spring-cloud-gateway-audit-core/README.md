# spring-cloud-gateway-audit-core

Core of the auditing plugin: the attribute collector, the per-route and global filters,
the publishing SPI and a default publisher. Add a
[provider module](../README.md#modules) to push events to a backend.

## Dependency

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-audit-core</artifactId>
</dependency>
```

## Audited attributes

Attributes are grouped so each group can be enabled or disabled independently.

| Group      | Attributes |
|------------|------------|
| `jwt`      | `jwt.client.id`, `jwt.impersonator.user.id`, `jwt.impersonator.user.name`, `jwt.issuer.id`, `jwt.user.id` |
| `request`  | `request.header.accept`, `request.header.content-length`, `request.header.content-type`, `request.ip`, `request.method`, `request.parameters`, `request.path` |
| `response` | `response.header.content-length`, `response.header.content-type`, `response.status` |
| `trace`    | `trace.id`, `span.id` |

Absent values are rendered as `_none_`; an expected-but-unresolved content type is
rendered as `unknown`.

`jwt.user.id` is the JWT `preferred_username` (falling back to `sub`), or the Basic-auth
user name when the request is authenticated with Basic credentials. `jwt.client.id` reads
`azp` (falling back to `client_id`). The impersonator attributes read the RFC 8693 `act`
(actor) claim.

## Where auditing runs

- Per route: add the `Audit` gateway filter to a route.
- Globally: enable the auditing web filter to audit every request.

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          audit:
            enabled: true            # master switch (default true)
            provider:                # kafka | redis | r2dbc ; unset = default publisher
            groups:
              jwt: true              # default true
              request: true          # default true
              response: true         # default true
              trace: true            # default true
            web-filter:
              enabled: false         # global auditing, opt-in (default false)
          routes:
            - id: patient
              uri: https://backend
              predicates:
                - Path=/patient/**
              filters:
                - Audit
```

## Configuration properties

All keys are under `spring.cloud.gateway.server.webflux.audit`.

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master switch; when `false` no audit filter is registered |
| `provider` | _(unset)_ | Provider selector, read by the provider modules |
| `groups.jwt` / `groups.request` / `groups.response` / `groups.trace` | `true` | Toggle each attribute group |
| `web-filter.enabled` | `false` | Register the global auditing web filter |

## Default publisher

Without a provider module, the default publisher logs each event at `DEBUG` and
republishes it as a Spring `AuditApplicationEvent`. Forward it with a listener:

```java
@Component
class AuditListener {

    @EventListener
    void on(AuditApplicationEvent event) {
        Map<String, String> attributes = event.getAuditEvent().attributes();
        // forward attributes to your backend
    }
}
```

## Custom publisher

Replace the publisher by declaring your own `AuditEventPublisher` bean; the default (and
any provider) then backs off:

```java
@Bean
AuditEventPublisher customAuditEventPublisher() {
    return event -> { /* ... */ };
}
```

Publishing happens after the response is produced. An `AuditEventPublisher` must not block
the event loop; offload blocking backends to their own scheduler.

## SPI

- `AuditEventPublisher` - functional interface `void publish(AuditEvent event)`.
- `AuditEvent` - `record(Instant timestamp, Map<String, String> attributes)`.
- `AuditEventSerializer` - renders `AuditEvent` attributes to a JSON string; reused by the
  provider modules.
- `AuditApplicationEvent` - Spring event wrapping an `AuditEvent`, published by the
  default publisher.
