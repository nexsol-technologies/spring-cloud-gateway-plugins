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
authenticated principal against the authorities the route requires.

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
        authorities: READ
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

The filter reads the current observation, so it needs `spring-boot-starter-actuator` and a
[tracer implementation](https://docs.spring.io/spring-boot/reference/actuator/tracing.html) on
the classpath.

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
