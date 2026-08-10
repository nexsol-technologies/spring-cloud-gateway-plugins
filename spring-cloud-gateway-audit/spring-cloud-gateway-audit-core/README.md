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
| `route`    | `route.id`, `route.metadata.<key>` for every metadata declared on that route |

Absent values are rendered as `_none_`; an expected-but-unresolved content type is
rendered as `unknown`.

The `trace` group is read from the current Micrometer Tracing observation, so it needs a
tracer that is actually wired. Since Spring Boot 4 that takes two dependencies &mdash; the
bridge *and* its auto-configuration module &mdash; and declaring only the bridge yields
empty `trace.id` and `span.id` on every event without any error. See
[wiring a real tracer](../../spring-cloud-gateway-filters/README.md#wiring-a-real-tracer)
for the Brave and OpenTelemetry pairs.

The `route` group answers *which route handled this call, and what does the configuration
say about it*. The metadata is read from the route that actually matched, so anything
declared there &mdash; the owning team, the tenant, a criticality level &mdash; travels
with the event to the audit backend:

```yaml
routes:
  - id: book
    uri: https://backend
    predicates:
      - Path=/book/**
    filters:
      - Audit
    metadata:
      tenant: acme
      criticality: high
```

audits `route.id=book`, `route.metadata.tenant=acme` and
`route.metadata.criticality=high`. An exchange no route handled &mdash; a request that
matched nothing, or a page the gateway served itself when the global web filter is on
&mdash; is audited as `route.id=_none_`, with no metadata attribute.

## Global metadata

What identifies the gateway rather than the exchange is declared once and stamped on every
event, under the `metadata.` prefix:

```yaml
audit:
  metadata:
    environment: prod
    datacenter: geneva
```

audits `metadata.environment=prod` and `metadata.datacenter=geneva` on every event. The two
prefixes are distinct namespaces, so a route metadata and a global one may share a name
without either overwriting the other (`metadata.tenant` and `route.metadata.tenant` coexist).

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
            metadata:                # stamped on every event under metadata.*
              environment: prod
            groups:
              jwt: true              # default true
              request: true          # default true
              response: true         # default true
              trace: true            # default true
              route: true            # default true
            web-filter:
              enabled: false         # global auditing, opt-in (default false)
              exclude-paths:         # paths the global filter never audits (default none)
                - /actuator/**
          routes:
            - id: book
              uri: https://backend
              predicates:
                - Path=/book/**
              filters:
                - Audit
```

### Excluding paths from the global filter

The global filter audits every exchange the gateway serves, including the ones it answers
itself. `web-filter.exclude-paths` takes it back: an exchange whose path matches one of the
patterns is passed straight through, so no event is built and nothing reaches the publisher.
The list is empty by default — what a gateway serves belongs to its audit trail unless it is
explicitly declared not to.

Two plugins fill that list in for themselves, so their own chatter never reaches the trail:

- the [gateway UI](../../spring-cloud-gateway-ui/README.md) adds the paths of its console,
  so browsing it does not fill the trail with its pages, fragments and assets. Only the
  exact paths the active views serve are excluded, never a `/ui/**` pattern: a gateway route
  declared under `/ui` keeps being audited.
- the [OpenAPI hub](../../spring-cloud-gateway-hub-openapi/README.md) adds its documentation
  endpoints, which a Swagger or Scalar console polls relentlessly. That list includes
  `/v3/api-docs/*`, the aggregated contracts, which *are* proxied routes &mdash; so this one
  does take genuinely routed traffic out of the trail.

Both are additive: what the application configured is kept, and only the missing paths are
appended. Neither affects the per-route `Audit` gateway filter, which audits whatever route
carries it.

## Configuration properties

All keys are under `spring.cloud.gateway.server.webflux.audit`.

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Master switch; when `false` no audit filter is registered |
| `provider` | _(unset)_ | Provider selector, read by the provider modules |
| `metadata.<key>` | _(empty)_ | Metadata added to every event as `metadata.<key>` |
| `groups.jwt` / `groups.request` / `groups.response` / `groups.trace` / `groups.route` | `true` | Toggle each attribute group |
| `web-filter.enabled` | `false` | Register the global auditing web filter |
| `web-filter.exclude-paths` | _(empty)_ | Path patterns the global filter never audits; a matching exchange produces no event at all |

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

Publishing happens once the exchange is over, whatever its outcome: an exchange that failed
&mdash; an upstream that refused the connection or timed out &mdash; is audited too, and the
failure is propagated afterwards. The `response` group of such an event only reports what
the response carried when the exchange failed, which is before the error handler wrote the
status. An `AuditEventPublisher` must not block the event loop; offload blocking backends
to their own scheduler.

## SPI

- `AuditEventPublisher` - functional interface `void publish(AuditEvent event)`.
- `AuditEvent` - `record(Instant timestamp, Map<String, String> attributes)`.
- `AuditEventSerializer` - renders `AuditEvent` attributes to a JSON string; reused by the
  provider modules.
- `AuditApplicationEvent` - Spring event wrapping an `AuditEvent`, published by the
  default publisher.
