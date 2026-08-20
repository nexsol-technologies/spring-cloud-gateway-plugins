# gateway-secured

**A combination** — port `8211`: the three plugins a secured gateway uses together, `oauth2`,
`filters` and `routes-security`. Each decides at a different moment, which is the whole point:

| Plugin | Decides | When |
| --- | --- | --- |
| `routes-security` | Is this route public? | **Before** authentication, by replaying the predicates |
| `oauth2` (resource server) | Is this token valid? | At authentication |
| `oauth2` / `filters` | Does this token grant *this* route? | After the route is resolved |

## Run it

```console
# from spring-cloud-gateway-samples/auth-server
mvn spring-boot:run
# from spring-cloud-gateway-samples/service-a
mvn spring-boot:run
# from here
mvn spring-boot:run
```

## Public before authentication

Spring Security runs before Spring Cloud Gateway resolves the route, so at security-filter
time the target route is not known yet. `routes-security` ships a matcher that replays the
route predicates against the exchange, exactly as the gateway does later, and matches when
the resolved route is flagged public.

Two routes, same backend, one line of metadata apart — see
[`public-routes.yaml`](src/main/resources/gateway-routes/public-routes.yaml):

```console
$ curl http://localhost:8211/public/sample -o /dev/null -w '%{http_code}\n'    # public: true
200
$ curl http://localhost:8211/private/sample -o /dev/null -w '%{http_code}\n'
401
```

## Authorized after the route is known

Get a token for a user, so it carries roles:

```console
$ TOKEN=$(curl -s -u messaging-client:secret \
    -d grant_type=client_credentials \
    http://localhost:9090/oauth2/token | jq -r .access_token)
```

| Url | Guarded by | Checks |
| --- | --- | --- |
| http://localhost:8211/secured/sample | `AuthorizationToken` (oauth2) | The token's `iss` is the sample authorization server |
| http://localhost:8211/secured-read/sample | `Authorization` (filters) | The principal carries the `READ` authority |

```console
$ curl -H "Authorization: Bearer $TOKEN" http://localhost:8211/secured/sample -o /dev/null -w '%{http_code}\n'
200
```

The two are not redundant. `AuthorizationToken` reads the **token** — its issuer, its client
id, its claims. `Authorization` reads the **authorities** Spring Security ended up with,
whatever produced them: a JWT here, Basic credentials in the
[gateway-filters](../gateway-filters/README.md) sample.

## The authority prefix

`/secured-read/**` asks for a bare `READ`, not `ROLE_READ`. The plugin's converter maps the
claim values to authorities **verbatim**, with no prefix added, and the sample authorization
server stamps `roles: ["READ"]` on a user's token. An account built with `roles("READ")` in
Java would carry `ROLE_READ` instead — same filter, different value, because the value is
whatever produced the authority.

A client_credentials token carries no user role, so `/secured-read/**` answers `403` for it
while `/secured/**` passes: the issuer is the same either way.

## Correlation

`correlation-id.enabled` is on, so every response — the 401s included — carries an
`x-correlation-id` header with the traceId of the exchange. A rejected call is exactly the one
you later want to find in the logs.
