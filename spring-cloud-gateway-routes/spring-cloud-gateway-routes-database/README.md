# spring-cloud-gateway-routes-database

Stores Spring Cloud Gateway route definitions in a relational database (R2DBC) and aggregates
them into the route locator. Routes are managed through a REST API, and through the view the
gateway UI console renders over them.

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol-tech.gateway</groupId>
           <artifactId>spring-cloud-gateway-routes-database</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## Management UI

The page over these routes belongs to the
[gateway UI console](../../spring-cloud-gateway-ui/README.md#database-routes-view), which
serves it at `/ui/routes/db` as it serves every other view: this plugin holds the routes,
the console renders them. Add `spring-cloud-gateway-ui` to the gateway and the view lights
up, with the **Database routes** entry in the side menu; without it, this plugin exposes
its REST API alone.

![The database routes view, inside the gateway UI shell](../../spring-cloud-gateway-ui/doc/routes-db-light.png)

What the console publishes there still follows what this plugin decides &mdash; see
[access](#access--what-is-published) below: under `read-only` the page renders without the
buttons that would change a route, and under `none` it does not appear at all.

## REST API

A reactive (Spring WebFlux) API for managing the stored routes, under `/api/gateway/routes`:

- **CRUD on routes** — create, read, update and delete route definitions at runtime.
- **Predicates and filters** — a route carries its predicates (the conditions matching a
  request) and its filters (the modifications applied to the request and the response), in the
  same form the gateway configuration uses.

## Turning it off

```yaml
spring.cloud.gateway.server.webflux.routes-database:
  enabled: false
```

Unwires the plugin: no locator, no services, no repositories, no endpoints. The routes
stored in the database stop feeding the gateway, which is a different decision from
refusing to publish the endpoints that manage them &mdash; that one is `access`, below.

## Who may change the routes

Reaching this API, or the page above, reconfigures the routing table. Three questions,
one property each, because they are not the same question:

| Question | Property | Values |
|---|---|---|
| **Is the plugin there** | `...routes-database.enabled` | `true` (default), `false` |
| **What** is published | `...routes-database.access` | `unrestricted` (default), `read-only`, `none` |
| **Who** may reach it | the security chain below, and the console | &mdash; |

### access &mdash; what is published

```yaml
spring.cloud.gateway.server.webflux.routes-database:
  access: read-only
```

- `unrestricted` &mdash; everything, which is what the plugin has always done.
- `read-only` &mdash; the routes can be listed and read; anything that would change one
  answers `405 Method Not Allowed`, and the view drops the buttons that would have led
  there.
- `none` &mdash; no endpoint at all: the controllers are not registered and the view does
  not appear in the console.

Whatever is set, **the database keeps feeding the gateway its routes**. This governs what
is published over HTTP, not where the routes come from &mdash; a gateway whose routes
arrive by migration can publish them for reading and refuse every change, without
authenticating anybody. That is the one answer available to a gateway that has no
authentication to offer.

### The security chain &mdash; who may reach it

When Spring Security is active, the plugin contributes its own `SecurityWebFilterChain`
asking for an authenticated principal on its API. Nothing has to be declared. It offers
HTTP Basic against whatever user directory the application declared, and Bearer tokens when
it configured an issuer; CSRF is off on that chain, since everything it matches is called
with a token and a JSON body by a client holding no session to carry one.

Two conditions are worth knowing, because they are what keeps this plugin from making a
gateway *less* safe by being added to it:

- **It backs off when no other `SecurityWebFilterChain` exists.** Spring Security serves
  its own "everything authenticated" chain as long as there is none, and that chain already
  covers these paths. Replacing it with one matching the route management alone would leave
  every other path of the application unguarded.
- **It backs off when there is nothing to authenticate against** &mdash; no user directory,
  no authentication manager, no issuer. Closing a door with no key behind it would leave no
  way through it at all. Such a gateway closes these endpoints with `access` instead.

The chain is ordered at
`GatewayDatabaseSecurityAutoConfiguration.ROUTE_MANAGEMENT_CHAIN_ORDER`
(`Ordered.HIGHEST_PRECEDENCE + 350`), behind the one the
[UI console](../../spring-cloud-gateway-ui/README.md) contributes: a gateway carrying the
console keeps a single place deciding who reaches what, and this chain never sees those
paths. Two escape hatches: declare your own bean named
`routeManagementSecurityWebFilterChain` (the plugin backs off), or turn it off:

```yaml
spring.cloud.gateway.server.webflux.routes-database:
  security-chain-enabled: false
```

> As with any `SecurityWebFilterChain` bean, its presence makes Spring Boot back off from
> its default "everything authenticated" chain &mdash; which is exactly why it is only
> contributed when that default is already gone.

### With the console

The module declares its API to the console through a `SecuredPaths.api(...)` bean from
`spring-cloud-gateway-commons`; the console declares the paths of the view it serves
itself. Either way they are declared as endpoints that change the gateway, so the console
asks for a principal on them **whether or not it is itself open**, under
`spring.cloud.gateway.server.webflux.ui.security.write-mode` &mdash; see
[the console's README](../../spring-cloud-gateway-ui/README.md#the-endpoints-that-change-the-gateway).
Its chain is ordered ahead of the one above, so a gateway carrying the console has one
place deciding who reaches what.
