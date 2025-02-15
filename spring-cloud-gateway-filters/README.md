# spring-cloud-gateway-filters

This project provides filters for Spring Cloud Gateway

```xml
    <properties>
        <spring-cloud-gateway-plugins.version>0.0.1-SNAPSHOT</spring-cloud-gateway-plugins.version>
    </properties>
    
    <dependencies>
        <dependency>
           <groupId>ch.nexsol.gateway</groupId>
           <artifactId>spring-cloud-gateway-filters</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## Filters

### Authorization

The `Authorization` filter validates Spring security `GrantedAuthority`, when Role Based Access Control (RBAC) is activated. 

usage: 

```yaml
spring.cloud.gateway:
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

### AuthorizationToken

The `AuthorizationToken` filter validates an access token (JWT). It retrieves the `Principal` from Spring Security or the `Authorization` header. If the token does not meet the validation rules for the route, the filter responds with HTTP status 403 Forbidden.

usage: 

```yaml
spring.cloud.gateway:
  routes:
  - id: test-authorization-token
    uri: http://localhost:8080
    predicates:
    - Path=/test
    filters:
    - name: AuthorizationToken
      args:
        issuers: # (optional) List of issuers (iss) to validate
        client-ids:  # (optional) List of client id (azp) to validate
        grant-accesses: # (optional) List of roles to validate. If many grant_access is provided, it is an AND validation: The token MUST have all the rules
        - jsonPath: '$.resource_access.*.roles'
          roles: "role-1,role-2"
```

### ConvertHttpMethod

The `ConvertHttpMethod` filter converts a http method to another. ex GET to POST

usage: 

```yaml
spring.cloud.gateway:
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
The `CorrelationId` filter adds the `x-correlation-id` header to the HTTP response. Its value is the `traceId` from Micrometer Tracing observation.
```yaml
spring.cloud.gateway:
  webfilter:
    correlation-id.enabled: true
```

This filter relies on Micrometer Tracing observation, so you need to include `spring-boot-starter-actuator` and provide a [tracer implementation](https://docs.spring.io/spring-boot/reference/actuator/tracing.html) in your classpath.


### Recaptcha

The `Recaptcha` filter verifies and validates a CAPTCHA score using Google's reCAPTCHA.
It provides a simple layer of protection for non-authenticated APIs.

usage: 

```yaml
spring.cloud.gateway:
  routes:
  - id: test-recaptcha
    uri: http://localhost:8080
    predicates:
    - Path=/test
    filters:
    - name: Recaptcha
      args:
        verify-url: the url of the site to validate the captcha.
        version: # (optional) the version of reCAPTCHA : V2 or V3. Default is V3.
        secretKey: # the secret key to use to validate captcha. It is generated at Google reCAPTCHA.
        recaptcha-http-header: #(optional) where to retreive the captcha in the http header. Default is 'recaptcha'
        score: # (optional) the minimal score to have for the request. (0 - 100). Default is '90'
```
