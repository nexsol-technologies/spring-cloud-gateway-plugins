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
        score:                 # (optional) minimal score to accept, 0 to 100; default 90
```
