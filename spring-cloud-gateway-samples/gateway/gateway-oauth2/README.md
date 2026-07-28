# gateway-oauth2

Exercises [spring-cloud-gateway-oauth2](../../../spring-cloud-gateway-oauth2/README.md) on its
own, on port `8202`.

## Running it

```console
# from spring-cloud-gateway-samples/auth-server
mvn spring-boot:run
# from here
mvn spring-boot:run
```

The [`auth-server`](../../auth-server) sample on `:9090` is required: the gateway fetches its
issuer metadata and validates every token against it.

## Getting a token

```console
$ TOKEN=$(curl -s -u messaging-client:secret \
    -d grant_type=client_credentials \
    http://localhost:9090/oauth2/token | jq -r .access_token)
```

## What it shows

### AuthorizationToken

Validates the access token against the rules declared on the route, and answers `403` when
they are not met.

| Url | Rule | Verdict |
| --- | --- | --- |
| http://localhost:8202/token/sample | `issuers: http://localhost:9090` | passes — the token comes from that issuer |
| http://localhost:8202/token-ko/sample | `issuers: https://another-issuer.example.org` | `403` |
| http://localhost:8202/token-roles/sample | `$.roles` must contain `READ` | `403` for a client_credentials token, which carries no user role |

```console
$ curl -H "Authorization: Bearer $TOKEN" http://localhost:8202/token-ko/sample -o /dev/null -w '%{http_code}\n'
403
```

The `token-roles` route is the one an authorization_code token satisfies: the sample
authorization server stamps a `roles` claim built from the **authenticated user's**
authorities, so `user:user` produces `roles: ["READ"]` while a client has none.

### BasicAuthExchangeToAccessToken

A request carrying the Basic credentials of a configured client has them exchanged for a
Bearer token before being forwarded. httpbin.org echoes the headers it received, which is
the proof:

```console
$ curl -u messaging-client:secret http://localhost:8202/exchange/headers | jq '.headers.Authorization'
"Bearer eyJraWQiOi..."
```

The token is cached until its `exp` claim, so the authorization server is not called again on
the next request. The plugin contributes the security chain matching those requests itself —
nothing about it is declared in this sample.

### Multitenancy

Two tenants are declared in [`application.yml`](src/main/resources/application.yml), `local`
and `local2`. They point at the same authorization server, since the sample only runs one;
in a real deployment each tenant keeps its own, and a request is validated against the
settings of the tenant that issued its token.
