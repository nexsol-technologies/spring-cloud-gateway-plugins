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
```

The `score` threshold applies to v3 only, where the provider answers a score between 0.0 and
1.0. The default of `50` is Google's recommended starting point: legitimate traffic commonly
scores between 0.7 and 0.9, so a stricter threshold turns real users away. In v2 the answer
carries no score and the threshold is ignored.

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
