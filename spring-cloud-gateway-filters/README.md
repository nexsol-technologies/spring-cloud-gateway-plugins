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

The filter `Authorization` validates Spring security `GrantedAuthority`, when Role Based Access Control (RBAC) is activated. 

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

The filter `AuthorizationToken` validates an access token (JWT). The filter takes the Principal provided by Spring Security or directly in the Authorization header. If the token is not validated with the rules configured for the route it will return http status 403 (Forbidden).

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
        client_ids:  # (optional) List of client id (azp) to validate
        grant_accesses: # (optional) List of roles to validate. If many grant_access is provided, it is an AND validation: The token MUST have all the rules
        - jsonPath: '$.resource_access.*.roles'
          roles: "role-1,role-2"
```

### ConvertHttpMethod

The filter `ConvertHttpMethod` converts a http method to another. ex GET to POST

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


### Recaptcha

The filter `Recaptcha` verifies and validates a CAPTCHA score using Google's reCAPTCHA.
Useful to non authenticated AIP's, offering a simple layer of protection with CAPTCHA.

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
