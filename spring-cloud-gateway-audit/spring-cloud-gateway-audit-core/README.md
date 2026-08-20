# spring-cloud-gateway-audit-core

The attribute collector, the per-route and global filters, the publishing SPI and a default
publisher. Add a [provider module](../README.md#modules) to push events to a backend.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-audit-core</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Where auditing runs

**Per route** — add the `Audit` gateway filter to the routes that need it:

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: book
    uri: https://backend
    predicates:
      - Path=/book/**
    filters:
      - Audit
```

**Globally** — turn the web filter on and every exchange is audited, including the ones the
gateway answers itself:

```yaml
spring.cloud.gateway.server.webflux.audit.web-filter:
  enabled: true
  exclude-paths:
    - /actuator/**
```

`exclude-paths` is empty by default: what a gateway serves belongs to its audit trail unless
declared otherwise. Two plugins append their own paths to it, so their chatter never reaches
the trail — the [console](../../spring-cloud-gateway-ui/README.md) adds the exact paths of
its active views (never a `/ui/**` pattern, so a gateway route declared under `/ui` keeps
being audited), and the [OpenAPI hub](../../spring-cloud-gateway-hub-openapi/README.md) adds
its documentation endpoints, which a Swagger console polls relentlessly. Both are additive
and neither affects the per-route `Audit` filter.

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.audit`.

```yaml
spring.cloud.gateway.server.webflux.audit:
  provider: kafka           # kafka | redis | r2dbc; unset = the default publisher
  metadata:                 # stamped on every event under metadata.*
    environment: prod
    datacenter: geneva
  masked-parameters:        # query parameters whose value becomes ***
    - access_token
    - signature
  groups:                   # each attribute group, all on by default
    jwt: true
    request: true
    response: true
    trace: true
    route: true
    validation: true
  web-filter:
    enabled: false          # global auditing, opt-in
    exclude-paths:
      - /actuator/**
```

| Property | Default | What it does |
| --- | --- | --- |
| `...audit.enabled` | `true` | Master switch; `false` registers no audit filter |
| `...audit.provider` | — | `kafka` \| `redis` \| `r2dbc`; read by the provider modules |
| `...audit.metadata.<key>` | — | Added to every event as `metadata.<key>` |
| `...audit.masked-parameters` | see below | Query parameters whose value is replaced by `***`, matched ignoring case |
| `...audit.groups.<group>` | `true` | Toggle one attribute group: `jwt`, `request`, `response`, `trace`, `route`, `validation` |
| `...audit.web-filter.enabled` | `false` | Register the global auditing web filter |
| `...audit.web-filter.exclude-paths` | — | Path patterns the global filter never audits; a match produces no event at all |

### Secrets in the query string

`request.parameters` audits the query string as it came in, and a gateway sees the query
strings of everyone behind it. A token, an authorization code or a password passed there
would otherwise land in Kafka, in Redis or in a table and outlive the request by as long as
the trail is kept.

`masked-parameters` defaults to `access_token`, `id_token`, `refresh_token`, `token`, `code`,
`client_secret`, `password`, `secret`, `api_key`, `apikey`. Only the values are replaced —
names, order and encoding are left as they were. Set it to an empty list to audit the query
string exactly as received.

## Audited attributes

| Group | Attributes |
| --- | --- |
| `jwt` | `jwt.client.id`, `jwt.impersonator.user.id`, `jwt.impersonator.user.name`, `jwt.issuer.id`, `jwt.user.id` |
| `request` | `request.header.accept`, `request.header.content-length`, `request.header.content-type`, `request.ip`, `request.method`, `request.parameters`, `request.path` |
| `response` | `response.header.content-length`, `response.header.content-type`, `response.status` |
| `trace` | `trace.id`, `span.id` |
| `route` | `route.id`, `route.metadata.<key>` for every metadata declared on the matched route |
| `validation` | `openapi.validation.operation`, `openapi.validation.request.valid`, `openapi.validation.request.errors`, `openapi.validation.response.valid`, `openapi.validation.response.errors` |

Absent values are rendered as `_none_`; an expected but unresolved content type as `unknown`.

* `jwt.user.id` is the JWT `preferred_username` falling back to `sub`, or the Basic-auth user
  name. `jwt.client.id` reads `azp` falling back to `client_id`. The impersonator attributes
  read the RFC 8693 `act` claim.
* `trace` is read from the current Micrometer Tracing observation, so it needs a tracer that
  is actually wired — since Spring Boot 4 that takes the bridge **and** its
  auto-configuration module, and declaring only the bridge yields empty ids without any
  error. See [wiring a real tracer](../../spring-cloud-gateway-filters/README.md#wiring-a-real-tracer).
* `route` reads the metadata of the route that actually matched, so anything declared there —
  owning team, tenant, criticality — travels with the event. An exchange no route handled is
  audited as `route.id=_none_`, with no metadata attribute.
* `validation` carries the outcome published by
  [spring-cloud-gateway-openapi-validation](../../spring-cloud-gateway-openapi-validation/README.md).
  Neither plugin depends on the other; nothing is added for an exchange no contract was
  applied to, and an `errors` attribute only appears when there were violations.

Global `metadata.*` and route `route.metadata.*` are distinct namespaces, so the two may share
a key name without either overwriting the other.

## SPI

| Type | What it is |
| --- | --- |
| `AuditEventPublisher` | Functional interface, `void publish(AuditEvent event)` |
| `AuditEvent` | `record(Instant timestamp, Map<String, String> attributes)` |
| `AuditEventSerializer` | Renders the attributes to a JSON string; reused by the providers |
| `AuditApplicationEvent` | Spring event wrapping an `AuditEvent`, published by the default publisher |

Without a provider module, the default publisher logs each event at `DEBUG` and republishes
it as an `AuditApplicationEvent`. Forward it with a listener, or replace the publisher
entirely — the default and any provider then back off:

```java
@Bean
AuditEventPublisher customAuditEventPublisher() {
    return event -> { /* ... */ };
}
```

Publishing happens once the exchange is over, whatever its outcome: an exchange that failed —
an upstream that refused the connection or timed out — is audited too, and the failure is
propagated afterwards. The `response` group of such an event reports what the response carried
at that moment, which is before the error handler wrote the status.

**An `AuditEventPublisher` must not block the event loop**; offload blocking backends to their
own scheduler.

## Sample

[gateway-audit](../../spring-cloud-gateway-samples/gateway/gateway-audit/README.md) — port
`8205`.
