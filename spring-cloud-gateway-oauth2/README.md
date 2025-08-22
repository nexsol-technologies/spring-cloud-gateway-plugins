# spring-cloud-gateway-oauth2

This plugin provides OAuth2 support for Spring Cloud Gateway.

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol.gateway</groupId>
           <artifactId>spring-cloud-gateway-oauth2</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## Filters

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

## Converter for GrantedAuthority

### Default Converter

By default, this plugin provides a converter to parse JWTs and extract Spring Security `GrantedAuthority`.

The following claims are searched in the access token to create `GrantedAuthority` entries:

| Claim Path                  | Description                                                                 |
|-----------------------------|-----------------------------------------------------------------------------|
| `$.realm_access.roles`      | In Keycloak, contains the global roles of the realm.                        |
| `$.resource_access.xxx.roles` | In Keycloak, contains client-specific roles for `xxx`. `xxx` defaults to the value of `spring.cloud.gateway.resourcename`, or `spring.application.name` if undefined. |
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

