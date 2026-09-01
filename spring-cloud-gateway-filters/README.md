# spring-cloud-gateway-filters

Custom gateway and web filters.

| Filter | Kind | What it does |
| --- | --- | --- |
| [`Authorization`](#authorization) | per route | Denies a request whose principal holds none of the required authorities |
| [`ConvertHttpMethod`](#converthttpmethod) | per route | Rewrites the HTTP method of the forwarded request |
| [`Maintenance`](#maintenance) | per route | Takes the route out of service for the duration of a maintenance window |
| [`Recaptcha`](#recaptcha) | per route | Verifies a CAPTCHA score before forwarding |
| [`IdentityPropagation`](#identitypropagation) | global | Writes who is calling, and who started the chain, onto the forwarded request |
| [`CorrelationId`](#correlationid) | global | Adds `x-correlation-id` to the response, carrying the current trace id |

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-filters</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Authorization

Checks the Spring Security `GrantedAuthority` entries of the authenticated principal against
the authorities the route requires. Holding **any one** of them is enough, and each is matched
as it is spelled — the `ROLE_` prefix included.

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: test-authorization
    uri: http://localhost:8080
    predicates:
      - Path=/test
    filters:
      - Authorization=READ,WRITE        # shorthand: a comma-separated list
      # or, in the long form:
      # - name: Authorization
      #   args:
      #     authorities: [READ, WRITE]
```

The filter fails closed: no authenticated principal is `401`, a principal holding none of the
authorities is `403`. It never relies on an upstream security filter chain to stop anonymous
traffic on its behalf.

## ConvertHttpMethod

Rewrites the HTTP method of the forwarded request — a `GET` received by the gateway reaching
the backend as a `POST`.

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: test-convert-http-method
    uri: http://localhost:8080
    predicates:
      - Path=/test
      - Method=GET
    filters:
      - ConvertHttpMethod=POST
```

## Maintenance

Takes a route out of service for the duration of a maintenance window. While the window is
open the route is not forwarded at all: the gateway answers on its own with the configured
status and a JSON body a front end can display.

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: test-maintenance
    uri: http://localhost:8080
    predicates:
      - Path=/test
    filters:
      - name: Maintenance
        args:
          message: The shop is closed until 4am.
          start: 2126-09-01T22:00:00Z
          end: 2126-09-02T02:00:00+02:00
          status: 593
          allowed-authorities: [ROLE_ADMIN]
          allowed-claims-match: ANY
          allowed-claims:
            - json-path: $.resource_access.*.roles
              values: [maintenance-bypass]
```

| Argument | Default | What it does |
| --- | --- | --- |
| `message` | `This service is temporarily unavailable for maintenance.` | The text the body carries |
| `start` | — | When the window opens, inclusive; unset means it is already open |
| `end` | — | When the window closes, exclusive; unset means it lasts until the configuration says otherwise |
| `status` | `593` | The status answered while the window is open, from 400 to 599 |
| `allowed-authorities` | `[]` | Authorities lifting the maintenance for their holder; holding any one is enough |
| `allowed-claims` | `[]` | Claims lifting the maintenance for their holder |
| `allowed-claims-match` | `ANY` | How the claims are combined: `ANY` for a caller satisfying one of them, `ALL` for every one |
| `allowed-claims[].json-path` | — | JSON path locating the claim in the token |
| `allowed-claims[].values` | — | Values that claim may hold |
| `allowed-claims[].match` | `ANY` | How the values of that claim are combined: `ANY` for one of them, `ALL` for every one |

The whole thing is optional: `- Maintenance` on its own closes the route to everyone, right
away and until the route is changed back.

### The response

```console
HTTP/1.1 593 Server Error (593)
content-type: application/json
retry-after: Mon, 02 Sep 2126 00:00:00 GMT

{
  "message": "The shop is closed until 4am.",
  "start": "2126-09-01T22:00:00Z",
  "end": "2126-09-02T02:00:00+02:00"
}
```

Both bounds are rendered as they were written, offset included, and are `null` when unset.
`Retry-After` carries the end of the window, which is exclusive and therefore the first
moment a retry can succeed. A window with no end carries no header at all: any value there
would be a return date the gateway invented.

`593` is not a status HTTP defines, which is the point &mdash; it separates a planned
outage from the `503` an overloaded or unreachable backend produces. Any status from 400 to
599 is accepted.

### The window

`start` and `end` are ISO-8601 date and time values **carrying their offset**:
`2025-09-01T22:00:00Z` or `2025-09-02T00:00:00+02:00`. A value without one is rejected when
the route is built &mdash; a gateway and its operator rarely sit in the same zone, and a
window opening at a local time nobody named is a window opening an hour off.

The bounds are compared as instants, so the two forms above are the same moment. `start` is
inclusive, `end` exclusive, and either can be left out: no `start` is a maintenance already
on, no `end` one that lasts until the configuration says otherwise.

### Letting a population through

An authority or a claim lifts the maintenance for whoever holds it, and holding any one of
the configured authorities is enough. The claims are combined by `allowed-claims-match`, so
a population defined by several claims at once is `ALL`, and one defined by any of them
`ANY`; the same choice applies inside a single claim through its own `match`. An authority
and a claim are never required together: satisfying either is enough.

A claim value is matched against what its JSON path resolves to &mdash; a list of roles, a
list of lists for a path carrying a wildcard, or a scalar compared as it prints, so
`maintenance_bypass: true` is matched by the value `true`. A claim holding a single string
is matched whole **and** token by token, splitting on commas and whitespace: the standard
`scope` claim is space separated, vendors write role lists with commas, and a claim naming
one value that contains a space still matches it.

> **The exemption is only as good as the filter chain in front of it.** The claims read
> here are those of the token Spring Security authenticated upstream, whose signature,
> expiry and issuer have therefore already been verified. The filter never reads the
> `Authorization` header itself, so a route exempting on claims has to sit behind a
> resource server filter chain &mdash; without one no request carries a principal, and the
> maintenance applies to everyone.

### Putting the whole gateway in maintenance

`default-filters` applies to every route the gateway serves:

```yaml
spring.cloud.gateway.server.webflux.default-filters:
  - name: Maintenance
    args:
      message: The platform is under maintenance.
      allowed-authorities: [ROLE_ADMIN]
```

Declare it **first** among the filters of a route: filters run in the order they are
written, so anything declared before it still runs on a request the maintenance is about to
refuse.

## Recaptcha

Verifies a CAPTCHA score against Google's reCAPTCHA — a layer of protection for the APIs
exposed without authentication.

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: test-recaptcha
    uri: http://localhost:8080
    predicates:
      - Path=/test
    filters:
      - name: Recaptcha
        args:
          verify-url: https://www.google.com/recaptcha/api/siteverify
          secret-key: ${RECAPTCHA_SECRET}
          version: V3
          action: submit_order
          hostnames: [shop.example.org]
          score: 50
          recaptcha-http-header: recaptcha
```

| Argument | Default | What it does |
| --- | --- | --- |
| `verify-url` | — | The reCAPTCHA verification endpoint |
| `secret-key` | — | The secret key issued by Google reCAPTCHA |
| `version` | `V3` | `V2` or `V3` |
| `recaptcha-http-header` | `recaptcha` | Header carrying the token |
| `score` | `50` | Minimal score to accept, 0 to 100; v3 only |
| `action` | — | The v3 action the token must have been solved for |
| `hostnames` | — | Host names the challenge may have been solved on |

`score` applies to v3 only, where the provider answers between 0.0 and 1.0. The default of
`50` is Google's recommended starting point — legitimate traffic commonly scores 0.7 to 0.9,
so a stricter threshold turns real users away. In v2 the answer carries no score.

> **Set `action` and `hostnames`.** Google verifies the token, not its origin. Without
> `action`, a v3 token solved on a harmless action of the site — a page view, a search — is
> replayable against the route this filter protects; without `hostnames`, a token solved on
> any other site registered under the same secret is accepted here. Left unset, each check is
> simply not made. Both need the `args` form above: the inline shorthand is positional.

**Everything that is not a pass is a 403.** No token, a token the provider rejected, a score
below the threshold, an unreachable or failing verification endpoint, an unreadable answer —
all deny with `403`, and the reason is logged rather than handed to the caller. The status of
the verification endpoint is never relayed: a misconfigured secret key is not the caller's
problem to see.

Calls go through a `recaptchaWebClient` bean of the plugin's own, never through the
application's `WebClient` — which may carry a base URL, the application's credentials or be
`@LoadBalanced`, none of which belong on a call to Google. It is derived from the application
`WebClient.Builder` when there is one, so codecs and customizers still apply. For a proxy,
mTLS or a custom timeout, declare the bean yourself:

```java
@Bean
WebClient recaptchaWebClient(WebClient.Builder builder) {
    return builder.filter(myProxyFilter()).build();
}
```

## IdentityPropagation

Writes the identity behind a request onto the request the gateway forwards, as two sets of
headers: who is calling now, and who started the chain.

Where a service reaches another one through the gateway, it presents a token of its own — so
the token of a given hop only ever names the last caller. The `origin` headers carry the
identity the chain started from, all the way down, which is what a downstream service needs to
log, audit or authorise against a caller it never sees.

```yaml
spring.cloud.gateway.server.webflux.identity-propagation:
  # Off by default: it rewrites headers on every routed request.
  enabled: true
  # Clients whose origin headers are believed. Empty means none.
  internal-clients: [service-a, service-b]
```

| Property | Default | What it does |
| --- | --- | --- |
| `...identity-propagation.enabled` | `false` | Registers the filter |
| `...identity-propagation.internal-clients` | `[]` | Client ids whose incoming origin headers are believed; exact, case-sensitive match |
| `...identity-propagation.current.issuer` / `.client` / `.user` | `x-issuerid` / `x-clientid` / `x-userid` | Headers naming the caller of this hop |
| `...identity-propagation.origin.issuer` / `.client` / `.user` | `x-origin-issuerid` / `x-origin-clientid` / `x-origin-userid` | Headers naming the identity the chain started from |

The client is read from `azp`, then `client_id`; the user from `preferred_username`, then
`sub` — the same precedence the audit plugin uses.

> **The origin is only believed from a declared internal client.** These are headers, and
> anyone calling the gateway from outside can send them. Every header the filter owns is
> removed from the incoming request and written again from the validated token; the one
> exception is a caller whose client id is listed in `internal-clients`, whose origin headers
> were put there by the gateway on an earlier hop.

An internal client carrying no origin is the start of a chain of its own — a scheduled job
calling another service is nobody's second hop — so it *becomes* the origin. A request with no
token has no identity to propagate: the headers are removed and nothing is written back.

### Reaching the logs and traces of the services downstream

The gateway sets plain HTTP headers, so they arrive whatever the tracing setup. A service that
declares them as baggage gets them in its trace context and its log MDC for free — **this
belongs in the configuration of the services, not of the gateway**:

```yaml
# in service-a, service-b, … — NOT in the gateway
management.tracing.baggage:
  remote-fields: [x-origin-issuerid, x-origin-clientid, x-origin-userid]
  correlation.fields: [x-origin-clientid, x-origin-userid]
```

> **Declaring these fields as `remote-fields` on the gateway undoes the filter.** A remote
> baggage field is extracted from the incoming request into the trace context, then injected
> into the outgoing one *after* every gateway filter has run — so the header this filter
> stripped and rewrote would be overwritten by the forged one on the way out.

How a remote field travels depends on the bridge: Brave sends one header per field, which is
what the gateway writes; the OpenTelemetry bridge uses the single W3C `baggage` header
instead, and a service behind an OTel chain has to read the headers itself.

Beware `tag-fields`: it puts a baggage field on every span as a tag, and a field carrying the
user is unbounded — fine for a trace store, fatal for anything deriving metrics from span tags.

## CorrelationId

Adds an `x-correlation-id` header to the response, carrying the `traceId` of the current
Micrometer Tracing observation.

```yaml
spring.cloud.gateway.server.webflux.webfilter.correlation-id.enabled: true
```

### Wiring a real tracer

The filter reads the current observation, so it needs `spring-boot-starter-actuator` and a
[tracer implementation](https://docs.spring.io/spring-boot/reference/actuator/tracing.html).
**Since Spring Boot 4 the bridge alone is not enough**: the auto-configuration wiring it moved
into a module of its own, which has to be declared too.

```xml
<!-- Brave -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-micrometer-tracing-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>

<!-- OpenTelemetry -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-micrometer-tracing-opentelemetry</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
```

Both pairs are version-managed by the Spring Boot BOM. `spring-boot-starter-opentelemetry`
bundles the OpenTelemetry pair and adds the OTLP exporter; it then tries to reach a collector
on `localhost:4318`, so set `management.tracing.export.enabled: false` until one is running.

**Declaring the bridge without its auto-configuration module fails silently.** No bean creates
a `Tracer`, so `NoopTracerAutoConfiguration` supplies `Tracer.NOOP`, every observation carries
`Span.NOOP`, and `span.context().traceId()` is the empty string. The filter finds nothing to
write and adds no header — no warning, no error. The same empty trace id shows up in the log
MDC and in the `trace.*` attributes of the
[audit plugin](../spring-cloud-gateway-audit/spring-cloud-gateway-audit-core/README.md).

## Sample

[gateway-filters](../spring-cloud-gateway-samples/gateway/gateway-filters/README.md) — port
`8201`.
