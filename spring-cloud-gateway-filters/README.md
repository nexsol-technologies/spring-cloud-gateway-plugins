# spring-cloud-gateway-filters

Custom gateway and web filters.

| Filter | Kind | What it does |
| --- | --- | --- |
| [`Authorization`](#authorization) | per route | Denies a request whose principal holds none of the required authorities |
| [`ConvertHttpMethod`](#converthttpmethod) | per route | Rewrites the HTTP method of the forwarded request |
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
