# spring-cloud-gateway-oauth2

OAuth2 support for Spring Cloud Gateway: per-route access token validation, an exchange of
Basic credentials for a Bearer token, JWT authority mapping and multi-tenant issuers.

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

The `AuthorizationToken` filter **authorizes** a request against the access token (JWT) it
carries: the issuer, the client id and the granted accesses declared on the route are checked,
and a token that does not meet them is answered with `403 Forbidden`.

It does **not** authenticate. The only token it reads is the one Spring Security already
authenticated — the `JwtAuthenticationToken` of the `Principal` — whose signature, expiry and
issuer have therefore been verified by the resource server of the application, single-issuer or
multi-tenant. The raw `Authorization` header is never parsed: the claims of a token nobody
verified are attacker-controlled, and authorizing on them would let anyone forge the very
issuer, client id and roles this filter checks.

**A route declaring a rule must therefore sit behind a resource server filter chain.** Without
one no request carries a principal, and every request is answered `401 Unauthorized`. See the
resource server section above to configure the issuers.

A request carrying no authenticated token is answered with `401 Unauthorized` as soon as the
route declares at least one rule: the filter never lets an unauthenticated request through. Two
cases are left untouched, as no rule applies to them: a filter declared without any argument,
and a route flagged public through its `public` metadata (see
`spring-cloud-gateway-routes-security`), which is served without authentication by design.

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
        grant-accesses-match: ALL # (optional) How the granted accesses are combined, ALL (default) or ANY
        grant-accesses: # (optional) List of roles to validate
        - jsonPath: '$.resource_access.*.roles'
          match: ALL # (optional) How the roles are combined, ALL (default) or ANY
          roles: "role-1,role-2"
```

#### Combining the granted accesses

Two independent match modes decide how the rules are combined, both defaulting to `ALL`:

* `grant-accesses-match` — between the granted accesses, so between the claims their JSON paths
  point at: `ALL` requires every one of them, `ANY` a single one.
* `match` — between the roles of a single granted access: `ALL` requires the claim to hold every
  role, `ANY` a single one.

The default `ALL`/`ALL` is the most restrictive combination, so a configuration that does not
declare a match mode keeps requiring every claim and every role.

The example below reads as *(`admin` or `service`) and `book_read`*: a realm role among
two, plus one client role of `book-service`. The bracket notation is what addresses a client id
holding a hyphen.

```yaml
    - name: AuthorizationToken
      args:
        grant-accesses-match: ALL
        grant-accesses:
        - jsonPath: "$.realm_access.roles"
          match: ANY
          roles:
          - admin
          - service
        - jsonPath: "$.resource_access['book-service'].roles"
          roles:
          - book_read
```

A wildcard such as `$.resource_access.*.roles` flattens the roles of every client into a single
list, so a role granted by any client satisfies the rule. Name the client explicitly when the rule
is meant to be about one of them.

## WebFilter

### BasicAuthExchangeToAccessToken

Intercepts a request carrying a Basic authentication header, exchanges those credentials for an
access token at an OAuth2 server, and replaces the header with the resulting Bearer token before
the request is forwarded downstream. Tokens are cached until they expire, so the authorization
server is not called on every request.

* **Basic to Bearer** — downstream services see Bearer authentication, whatever the caller sent.
* **Client Credentials** — the exchange uses the standard OAuth 2.0 Client Credentials grant.
* **Caching** — the access token is cached in memory and evicted on its JWT `exp` claim, so an
  expired token is never reused. The application `CacheManager` is used when there is one;
  otherwise the filter falls back to its own in-memory cache, so the host application needs no
  caching setup.

```yaml
spring.cloud.gateway.server.webflux:
  webfilter:
    basicauth-exchange-oauth2:
      token-uris:
        user1: https://my-authorization-server/protocol/openid-connect/token
        user2: https://keycloak/realms/test/protocol/openid-connect/token
        user3: https://keycloak/realms/test/protocol/openid-connect/token
```

#### Spring Security Integration

The plugin contributes the security filter chain itself, as soon as at least one `token-uris`
entry is configured: it matches the requests carrying the Basic credentials of a configured
client, disables the standard HTTP Basic authentication for them, and inserts the exchange
filter before authentication. Nothing has to be declared in the application.

The chain is ordered at `BasicAuthExchangeSecurityAutoConfiguration.BASIC_AUTH_EXCHANGE_CHAIN_ORDER`
(`Ordered.HIGHEST_PRECEDENCE + 200`), ahead of the chains an application usually declares from `@Order(1)`.

Two escape hatches:

* declare your own bean named `basicAuthExchangeSecurityWebFilterChain` — the plugin backs off;
* or turn the chain off entirely:

```yaml
spring.cloud.gateway.server.webflux:
  webfilter:
    basicauth-exchange-oauth2:
      security-chain-enabled: false
```

> As with any `SecurityWebFilterChain` bean, its presence makes Spring Boot back off from its
> default "everything authenticated" chain. An application that was relying on that default
> must declare its own chains.


## GrantedAuthority converter

### Default converter

The plugin parses the JWT and maps its claims to Spring Security `GrantedAuthority` entries.

The following claims are read from the access token:

| Claim Path                  | Description                                                                 |
|-----------------------------|-----------------------------------------------------------------------------|
| `$.realm_access.roles`      | In Keycloak, contains the global roles of the realm.                        |
| `$.resource_access.xxx.roles` | In Keycloak, contains client-specific roles for `xxx`. `xxx` defaults to the value of `spring.cloud.gateway.server.webflux.oauth2.resource-name`, or `spring.application.name` if undefined. |
| `$.permissions`             | Additional permissions.                                                     |
| `$.roles`                   | General roles.                                                              |

### Configurable converter

Declare your own claim paths to map further claims to `GrantedAuthority`:

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

A gateway can accept tokens from several identity providers at once: each request is validated
against the settings of the tenant that issued its token, so every tenant keeps its own
authorization server.

### Tenant-specific configuration

Declare one OIDC issuer URI per tenant. The issuer URI is the discovery endpoint returning the
OpenID Connect or OAuth 2.0 metadata of that authorization server.

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

### Dynamic tenant identification *(not implemented)*

Resolving the tenant from the request itself — a header, a subdomain — is not supported yet.

