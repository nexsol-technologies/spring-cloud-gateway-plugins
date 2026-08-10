# gateway-ui-secured

The console of [spring-cloud-gateway-ui](../../../spring-cloud-gateway-ui/README.md) behind
its own login page, on port `8213`: a local user out of the box, and single sign-on through
a Keycloak the sample brings with it.

The sample declares **no security bean and no filter chain**. The plugin contributes the
chain; the whole configuration is:

```yaml
spring.cloud.gateway.server.webflux.ui.security:
  mode: authenticated
  user:
    name: superadmin
    password: ${ADMIN_PASSWORD:superadmin}
```

The rest is the three starters in the `pom.xml` &mdash; `security`, `oauth2-client` and
`oauth2-resource-server` &mdash; and a profile naming the realm.

## The local user alone

```console
mvn spring-boot:run
```

Open http://localhost:8213/ui: it redirects to `/ui/login`. Sign in with
`superadmin` / `superadmin` (set `ADMIN_PASSWORD` to change it) and the shell opens on the
page you were heading for. The side menu then shows who is signed in, with the button that
ends the session next to the theme switch.

Nothing else is running under this profile: no Keycloak, no Docker.

## With Keycloak

```console
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=keycloak
```

The compose file starts Keycloak on `8380` and imports
[keycloak/gateway-realm.json](keycloak/gateway-realm.json) at start-up. The realm lives in
the container only, so `docker compose down` puts everything back the way it was.

What the realm holds:

| | |
| --- | --- |
| Realm | `gateway` |
| Client | `gateway-console`, confidential, secret `gateway-console-secret` |
| Redirect URI | `http://localhost:8213/login/oauth2/code/keycloak` |
| `operator` / `operator` | holds `ADMIN` &mdash; reaches the console |
| `visitor` / `visitor` | holds `READER` &mdash; signs in, then gets a `403` |
| Keycloak admin | `admin` / `admin` on http://localhost:8380 |

Signing out goes through Keycloak's end-session endpoint, so the realm session ends with the
console one and the next sign-in asks for credentials again &mdash; which is what lets you
try `operator` and `visitor` one after the other. The realm registers
`http://localhost:8213/ui/login*` as a valid post-logout destination for that to be
accepted.

The login page now shows a **Sign in with Keycloak** button next to the credentials form.
Both ways in are offered at once, which is the point: operators go through the provider, and
the local user stays as the way in when the provider is unreachable.

Signing in as `visitor` is worth doing once: the exchange with Keycloak succeeds and the
console still turns them away, because the profile narrows it to the principals holding
`ADMIN`. They land on a page saying so, with the button that ends the session &mdash; not on
a bare `403`, which would leave them signed in with no way out and no way in:

```yaml
spring.cloud.gateway.server.webflux.ui.security:
  roles-claim: realm_access.roles
  required-roles: [ADMIN]
```

`roles-claim` is a dotted path into the claim set. The imported realm carries a **realm
roles** mapper writing them to `realm_access.roles` in the identity token as well as in the
access token, so a session and a Bearer token are matched on the same rule.

## Calling the console with a token

The `keycloak` profile also turns the endpoints of the console into a resource server, so a
script reads them without a browser session:

```console
TOKEN=$(curl -s -d grant_type=password -d client_id=gateway-console \
  -d client_secret=gateway-console-secret -d username=operator -d password=operator \
  http://localhost:8380/realms/gateway/protocol/openid-connect/token | jq -r .access_token)

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8213/ui/metrics/data
```

Without a token that same call answers `401` with `WWW-Authenticate: Bearer` rather than the
HTML login page.

## Why the provider sits in a profile

`issuer-uri` makes the gateway fetch the OpenID configuration of the realm at start-up. In
`application.yml` that would make the sample &mdash; and its tests &mdash; refuse to start
unless Keycloak is up. In a profile, the default sample stays runnable on its own.

## What it does not show

This sample runs only the UI plugin, so the menu has no **Database routes** and no **Audit**
entry. The [gateway-full](../gateway-full/README.md) sample is the same shell with every
plugin present; [gateway-secured](../gateway-secured/README.md) is the other half of the
picture &mdash; securing the *traffic* the gateway routes rather than the console that
watches it.
