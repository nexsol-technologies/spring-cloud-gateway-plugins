# spring-cloud-gateway-oauth2

OAuth2 support for Spring Cloud Gateway: per-route access token authorization, an exchange of
Basic credentials for a Bearer token, JWT authority mapping and multi-tenant issuers.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-oauth2</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## AuthorizationToken (per-route filter)

**Authorizes** a request against the access token it carries: the issuer, the client id and the
granted accesses declared on the route are checked, and a token that does not meet them is
answered `403 Forbidden`.

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: test-authorization-token
    uri: http://localhost:8080
    predicates:
      - Path=/test
    filters:
      - name: AuthorizationToken
        args:
          issuers: [https://keycloak/realms/test]   # accepted iss values
          client-ids: [book-client]                 # accepted azp values
          grant-accesses-match: ALL                 # between the granted accesses
          grant-accesses:
            - jsonPath: "$.realm_access.roles"
              match: ANY                            # between the roles of this access
              roles: [admin, service]
            - jsonPath: "$.resource_access['book-service'].roles"
              roles: [book_read]
```

| Argument | Default | What it does |
| --- | --- | --- |
| `issuers` | — | Issuers (`iss`) the token may come from |
| `client-ids` | — | Client ids (`azp`) the token may have been issued to |
| `grant-accesses` | — | Claim paths and the roles they must carry |
| `grant-accesses-match` | `ALL` | Combines the granted accesses: `ALL` requires every one, `ANY` a single one |
| `grant-accesses[].match` | `ALL` | Combines the roles of one granted access |

The example above reads as *(`admin` or `service`) **and** `book_read`*. The bracket notation is
what addresses a client id holding a hyphen. A wildcard such as `$.resource_access.*.roles`
flattens the roles of every client into one list, so a role granted by any client satisfies the
rule — name the client explicitly when the rule is meant to be about one of them.

**It does not authenticate.** The only token it reads is the one Spring Security already
authenticated — the `JwtAuthenticationToken` of the `Principal` — whose signature, expiry and
issuer have been verified by the resource server. The raw `Authorization` header is never
parsed: the claims of a token nobody verified are attacker-controlled, and authorizing on them
would let anyone forge the very issuer, client id and roles this filter checks.

> **A route declaring a rule must sit behind a resource server filter chain.** Without one no
> request carries a principal, and every request is answered `401`.

Two cases are left untouched, no rule applying to them: a filter declared without any argument,
and a route flagged public through its `public` metadata (see
[routes-security](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-security/README.md)).

## BasicAuthExchangeToAccessToken (web filter)

Intercepts a request carrying Basic credentials, exchanges them for an access token at an
OAuth2 server through the Client Credentials grant, and replaces the header with the resulting
Bearer token before forwarding. Downstream services see Bearer authentication, whatever the
caller sent.

```yaml
spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2:
  # One token endpoint per Basic user name.
  token-uris:
    user1: https://my-authorization-server/protocol/openid-connect/token
    user2: https://keycloak/realms/test/protocol/openid-connect/token
  # Same thing, for a user whose exchange needs more than an endpoint.
  clients:
    user3:
      token-uri: https://my-authorization-server/connect/token
      scopes:
        - read
        - write
  # Set to false to keep the plugin from contributing its own security chain.
  security-chain-enabled: true
```

| Property | Default | What it does |
| --- | --- | --- |
| `...basicauth-exchange-oauth2.enabled` | `true` | Master switch; `false` registers neither the filter nor its security chain |
| `...basicauth-exchange-oauth2.token-uris.<user>` | — | Token endpoint used for that Basic user; one entry is enough to activate the filter |
| `...basicauth-exchange-oauth2.clients.<user>.token-uri` | — | Token endpoint used for that Basic user; one entry is enough to activate the filter |
| `...basicauth-exchange-oauth2.clients.<user>.scopes` | — | Scopes requested for that user; when empty no `scope` parameter is sent at all |
| `...basicauth-exchange-oauth2.credentials-in-query-param` | `false` | Whether Basic credentials are also read from a query parameter |
| `...basicauth-exchange-oauth2.credentials-query-param-name` | `_auth` | Name of that query parameter |
| `...basicauth-exchange-oauth2.security-chain-enabled` | `true` | Whether the plugin contributes its own `SecurityWebFilterChain` |

Declare a user under `token-uris` or under `clients`, not both; `clients` wins over `token-uris`
for a user that ends up in the two. The forms are equivalent for a user needing no scope. Both
are validated at startup: a client declared without its token endpoint fails the context rather
than every one of its requests. An empty `scopes` list is not an empty
`scope` parameter: the exchange sends none, where an authorization server would answer
`invalid_scope` instead of granting the client its default scopes.

Tokens are cached in memory and evicted on their JWT `exp` claim, so an expired token is never
reused and the authorization server is not called on every request. The application
`CacheManager` is used when there is one, otherwise the filter falls back to its own cache —
the host application needs no caching setup.

**Credentials in a query parameter.** A caller that cannot set an `Authorization` header can
carry the same Base64 `client-id:client-secret` pair in a query parameter, once
`credentials-in-query-param` is on. The header still wins whenever it carries usable
credentials, and the parameter is dropped from the request forwarded downstream. Weigh it before
turning it on: a credential in a URL is written to access logs, proxy logs, browser history and
`Referer` headers, none of which the gateway controls.

**Spring Security integration.** As soon as one client is configured, the plugin contributes
the chain itself, and nothing has to be declared in the application. A request whose
credentials were exchanged is let through it, rather than refused by an application that
demands a principal and finds no Basic credentials left to authenticate — the filter replaced
them with a bearer token before Spring Security ever looked.

The chain permits what it matches, the exchange being what authorizes: a request only reaches
it once the authorization server has accepted the client secret and issued the token it now
carries. Wrong secret, unreachable server, refused grant — the filter answers `401` and nothing
is forwarded.

It matches on an attribute the filter sets, never on the `Authorization` header. The header
cannot serve: the filter is a `WebFilter` bean, registered globally at
`HIGHEST_PRECEDENCE + 5`, far ahead of the `WebFilterChainProxy` at `-100`, so by the time
matchers run the header is already a bearer one. Matching what was actually exchanged also
means a caller cannot select this chain with a client id alone — client ids sit in the
configuration and are not secrets.

The chain is ordered at
`BasicAuthExchangeSecurityAutoConfiguration.BASIC_AUTH_EXCHANGE_CHAIN_ORDER`
(`Ordered.HIGHEST_PRECEDENCE + 200`), ahead of the chains an application usually declares from
`@Order(1)`. Two escape hatches, for a gateway that would rather validate the resulting token
itself or keep its own rules over these requests: declare your own bean named
`basicAuthExchangeSecurityWebFilterChain`, or set `security-chain-enabled: false` — the
exchange still happens, only the letting through is gone.

> As with any `SecurityWebFilterChain` bean, its presence makes Spring Boot back off from its
> default "everything authenticated" chain. An application relying on that default must declare
> its own chains.

## Mapping claims to authorities

The plugin parses the JWT and maps these claims to Spring Security `GrantedAuthority` entries:

| Claim path | What it holds |
| --- | --- |
| `$.realm_access.roles` | Keycloak: the global roles of the realm |
| `$.resource_access.<client>.roles` | Keycloak: the client-specific roles. `<client>` defaults to `...webflux.oauth2.resource-name`, or `spring.application.name` |
| `$.permissions` | Additional permissions |
| `$.roles` | General roles |

Declare further paths to map more claims:

```yaml
spring.security.oauth2.resourceserver.granted-authorities-mapping:
  json-path:
    - '$.my-custom-roles'
```

## Multitenancy

A gateway can accept tokens from several identity providers at once: each request is validated
against the settings of the tenant that issued its token, so every tenant keeps its own
authorization server. Declare one OIDC issuer URI per tenant — the discovery endpoint returning
that server's metadata:

```yaml
spring.security.oauth2.resourceserver.multitenant:
  - id: keycloak
    issuer-uri: https://keycloakhost:keycloakport/realms/{realm}
  - id: okta
    issuer-uri: https://{yourOktaOrg}
```

> Resolving the tenant from the request itself — a header, a subdomain — is **not implemented**.

## Sample

[gateway-oauth2](../spring-cloud-gateway-samples/gateway/gateway-oauth2/README.md) — port
`8202`, with the `auth-server` sample on `9090`.
