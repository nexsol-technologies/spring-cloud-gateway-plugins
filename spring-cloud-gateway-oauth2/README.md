# spring-cloud-gateway-oauth2

This plugin provides OAuth2 support for Spring Cloud Gateway.

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol-tech.gateway</groupId>
           <artifactId>spring-cloud-gateway-oauth2</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## GatewayFilter Factories

### AuthorizationToken

The `AuthorizationToken` filter validates an access token (JWT). It retrieves the `Principal` from Spring Security or the `Authorization` header. If the token does not meet the validation rules for the route, the filter responds with HTTP status 403 Forbidden.

usage: 

```yaml
spring.cloud.gateway.server.webflux:
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

## WebFilter

### BasicAuthExchangeToAccessToken

This Spring Cloud Gateway filter is designed to intercept incoming requests containing a Basic authentication header, exchange it for a Bearer token (Access Token) from an OAuth2 server, and replace the Basic header with the Bearer token for transmission to downstream services.
It implements a token expiration-based caching mechanism to optimize performance and minimize calls to the authorization server.
<h3> 🚀 Key Features </h3> 
<ul>
<li>Basic to Bearer Conversion: Replaces Basic authentication with Bearer authentication for downstream services.</li>
<li>Client Credentials Flow: Uses the standard OAuth 2.0 Client Credentials Grant flow.</li>
<li>Caching: Caches the access token in memory, relying on the JWT expiration date (exp) to ensure only valid tokens are used.</li>
</ul>  

usage:
```yaml
spring.cloud.gateway.server.webflux:
  webfilter:
    basicauth-exchange-oauth2:
      token-uris:
        user1: https://my-authorization-server/protocol/openid-connect/token
        user2: https://keycloak/realms/test/protocol/openid-connect/token
        user3: https://keycloak/realms/test/protocol/openid-connect/token
```

#### Spring Security Integration Example

To integrate this filter into the Spring Security WebFlux chain, you typically define a security filter chain that is conditionally activated only when a Basic Authorization header is detected. The custom filter is then added before the standard authentication process.

```
@Bean
@Order(1)
@Conditional(BasicAuthExchangeConfiguredCondition.class)
SecurityWebFilterChain basicWebFilterChain(ServerHttpSecurity http,
        BasicAuthExchangeToAccessTokenProperties properties,
        BasicAuthExchangeToAccessTokenGatewayWebFilter basicAuthExchangeGatewayWebFilter) {
...
    http.securityMatcher(SecurityUtils.authorizationHeaderBasicMatcher(properties));
    http.addFilterBefore(basicAuthExchangeGatewayWebFilter, SecurityWebFiltersOrder.AUTHENTICATION);
    return http.build();
}

```


## Converter for GrantedAuthority

### Default Converter

By default, this plugin provides a converter to parse JWTs and extract Spring Security `GrantedAuthority`.

The following claims are searched in the access token to create `GrantedAuthority` entries:

| Claim Path                  | Description                                                                 |
|-----------------------------|-----------------------------------------------------------------------------|
| `$.realm_access.roles`      | In Keycloak, contains the global roles of the realm.                        |
| `$.resource_access.xxx.roles` | In Keycloak, contains client-specific roles for `xxx`. `xxx` defaults to the value of `spring.cloud.gateway.server.webflux.oauth2.resource-name`, or `spring.application.name` if undefined. |
| `$.permissions`             | Additional permissions.                                                     |
| `$.roles`                   | General roles.                                                              |

### Configurable Converter

You can define custom paths to locate claims in the JWT and map them to Spring Security `GrantedAuthority`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        granted-authorities-mapping:
          json-path:
          - '$.my-custom-roles'
```


## Multitenancy

This plugin simplifies the implementation of multi-tenant OAuth2 authentication and JWT validation in Spring Cloud Gateway. It dynamically configures and validates requests based on tenant-specific OAuth2 settings, enabling seamless support for multiple tenants, each with its own identity provider.

### Tenant-Specific OAuth2 Configurations

You can configure an OIDC issuer URI for each tenant. The issuer URI serves as a discovery endpoint that returns OpenID Connect or OAuth 2.0 metadata for the authorization server.

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        multitenant:
          - id: keycloak
            issuer-uri: https://keycloakhost:keycloakport/realms/{realm}
          - id: okta
            issuer-uri: https://{yourOktaOrg}
```

### Dynamic Tenant Identification *(Not Implemented)*

Future versions may allow tenant information to be extracted dynamically from request headers, subdomains, or other sources.

