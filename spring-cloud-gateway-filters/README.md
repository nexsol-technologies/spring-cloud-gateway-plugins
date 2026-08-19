# spring-cloud-gateway-filters

Custom gateway and web filters for Spring Cloud Gateway.

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol-tech.gateway</groupId>
           <artifactId>spring-cloud-gateway-filters</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## Filters

### Authorization

The `Authorization` filter checks the Spring Security `GrantedAuthority` entries of the
authenticated principal against the authorities the route requires. Holding any one of
them is enough, and each is matched as it is spelled — the `ROLE_` prefix included.

The filter fails closed: a request carrying no authenticated principal is denied with
`401 Unauthorized`, and one whose principal holds none of the required authorities with
`403 Forbidden`. It never relies on an upstream security filter chain to stop anonymous
traffic on its behalf.

```yaml
spring.cloud.gateway.server.webflux:
  routes:
  - id: test-authorization
    uri: http://localhost:8080
    predicates:
    - Path=/test
    filters:
    - name: Authorization
      args:
        authorities:
        - READ
        - WRITE
```

The shortcut form takes the authorities as a comma-separated list:

```yaml
    filters:
    - Authorization=READ,WRITE
```

### ConvertHttpMethod

The `ConvertHttpMethod` filter rewrites the HTTP method of the forwarded request — a `GET`
received by the gateway reaching the backend as a `POST`, for instance.

```yaml
spring.cloud.gateway.server.webflux:
  routes:
  - id: test-convert-http-method
    uri: http://localhost:8080
    predicates:
    - Path=/test
    - Method=GET
    filters:
    - ConvertHttpMethod=POST
```

### IdentityPropagation

Writes the identity behind a request onto the request the gateway forwards, as two sets of
headers: who is calling now, and who started the chain.

Where a service reaches another one through the gateway, it presents a token of its own —
so the token of a given hop only ever names the last caller. The `origin` headers carry the
identity of the client the chain started from, all the way down, which is what a downstream
service needs to log, audit or authorise against a caller it never sees.

```yaml
spring.cloud.gateway.server.webflux:
  identity-propagation:
    enabled: true                     # off by default: it rewrites headers on every routed request
    internal-clients: [service-a, service-b]
```

| Header | Carries |
|---|---|
| `x-issuerid`, `x-clientid`, `x-userid` | the identity of the caller of this hop, from its validated token |
| `x-origin-issuerid`, `x-origin-clientid`, `x-origin-userid` | the identity the chain started from |

The names are configurable under `current.*` and `origin.*` (`issuer`, `client`, `user`).
The client is read from `azp`, then `client_id`; the user from `preferred_username`, then
`sub` — the same precedence the audit plugin uses.

> **The origin is only believed from a declared internal client.** These are headers, and
> anyone calling the gateway from outside can send them. Every header the filter owns is
> removed from the incoming request and written again from the validated token; the one
> exception is a caller whose `client_id` is listed in `internal-clients`, whose origin
> headers were put there by the gateway on an earlier hop. `internal-clients` is empty by
> default, so nothing is believed until you say so. Matching is exact: a client id is case
> sensitive.

An internal client that carries no origin is the start of a chain of its own — a scheduled
job calling another service is nobody's second hop — so it becomes the origin rather than
losing one. A request with no token has no identity to propagate: the headers are removed
and nothing is written back.

#### Reaching the logs and the traces of the services downstream

The gateway sets plain HTTP headers, so they arrive whatever the tracing setup. A service
that declares them as baggage picks them up and gets them in its trace context and its log
MDC for free — **this belongs in the configuration of the services, not of the gateway**:

```yaml
# in service-a, service-b, ... — NOT in the gateway
management.tracing.baggage:
  remote-fields: [x-origin-issuerid, x-origin-clientid, x-origin-userid]
  correlation.fields: [x-origin-clientid, x-origin-userid]
```

> **Declaring these fields as `remote-fields` on the gateway undoes the filter.** A remote
> baggage field is *extracted* from the incoming request into the trace context, then
> *injected* into the outgoing one when the gateway calls upstream — after every gateway
> filter has run. The injected value is the one that arrived, so the header this filter
> stripped and rewrote would be overwritten by the forged one on the way out. Leave the
> propagated names out of the gateway's `remote-fields`.

How a remote field travels depends on the bridge — Brave sends one header per field, which
is what the gateway writes; the OpenTelemetry bridge uses the single W3C `baggage` header
instead, and a service behind an OTel-based chain needs to read the headers itself. Check
it against the bridge you deploy.

Beware `tag-fields`: it puts a baggage field on every span as a tag, and a field carrying
the user is unbounded. That is fine for a trace store and fatal for anything deriving
metrics from span tags.

### CorrelationId

The `CorrelationId` filter adds an `x-correlation-id` header to the response, carrying the
`traceId` of the current Micrometer Tracing observation.

```yaml
spring.cloud.gateway.server.webflux:
  webfilter:
    correlation-id.enabled: true
```

#### Wiring a real tracer

The filter reads the current observation, so it needs `spring-boot-starter-actuator` and a
[tracer implementation](https://docs.spring.io/spring-boot/reference/actuator/tracing.html).
Since Spring Boot 4 the bridge alone is not enough: the auto-configuration that wires it
moved out of `spring-boot-autoconfigure` into a module of its own, which has to be declared
too. With Brave:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-micrometer-tracing-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
```

With OpenTelemetry:

```xml
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

Declaring the bridge without its auto-configuration module fails silently, which is worth
recognising: no bean creates a `Tracer`, so `NoopTracerAutoConfiguration` supplies
`Tracer.NOOP`, every observation carries `Span.NOOP`, and `span.context().traceId()` is the
empty string. The filter finds nothing to write and adds no header &mdash; no warning, no
error, just a response without `x-correlation-id`. The same empty trace id shows up in the
log MDC and in the `trace.*` attributes of the
[audit plugin](../spring-cloud-gateway-audit/spring-cloud-gateway-audit-core/README.md).

### Recaptcha

The `Recaptcha` filter verifies a CAPTCHA score against Google's reCAPTCHA, a layer of
protection for the APIs that are exposed without authentication.

```yaml
spring.cloud.gateway.server.webflux:
  routes:
  - id: test-recaptcha
    uri: http://localhost:8080
    predicates:
    - Path=/test
    filters:
    - name: Recaptcha
      args:
        verify-url:            # the reCAPTCHA verification endpoint
        version:               # (optional) V2 or V3; default V3
        secret-key:            # the secret key issued by Google reCAPTCHA
        recaptcha-http-header: # (optional) header carrying the captcha; default 'recaptcha'
        score:                 # (optional) minimal score to accept, 0 to 100; default 50
        action:                # (optional) v3 action the token must have been solved for
        hostnames:             # (optional) host names the challenge may have been solved on
```

The `score` threshold applies to v3 only, where the provider answers a score between 0.0 and
1.0. The default of `50` is Google's recommended starting point: legitimate traffic commonly
scores between 0.7 and 0.9, so a stricter threshold turns real users away. In v2 the answer
carries no score and the threshold is ignored.

`action` and `hostnames` are what bind a token to where it came from, and both are worth
setting. Google verifies the token, not its origin: without `action`, a v3 token solved on a
harmless action of the site — a page view, a search — is replayable against the route this
filter protects; without `hostnames`, a token solved on any other site registered under the
same secret is accepted here. Left unset, each check is simply not made. They take the `args`
form above rather than the inline shorthand, whose arguments are positional and comma
separated.

#### Everything that is not a pass is a 403

The filter forwards a request only once the token has been verified. Every other outcome —
no token, a token the provider rejected, a score below the threshold, an unreachable or
failing verification endpoint, an unreadable answer — denies the request with **403**, and
the reason is logged rather than handed to the caller. A refusal is not a failure of the
gateway and must not read as one, and the status of the verification endpoint is never
relayed: a misconfigured secret key is not the caller's problem to see.

#### The verification client

The calls to the verification endpoint go through a `recaptchaWebClient` bean of the
plugin's own, never through the application's `WebClient`. That one may carry a base URL,
the application's credentials or be `@LoadBalanced`, none of which belong on a call to
Google. It is derived from the application `WebClient.Builder` when there is one, so codecs
and customizers still apply. For anything more — a proxy, mTLS, a custom timeout — declare
the bean yourself and it is used instead:

```java
@Bean
WebClient recaptchaWebClient(WebClient.Builder builder) {
    return builder.filter(myProxyFilter()).build();
}
```
