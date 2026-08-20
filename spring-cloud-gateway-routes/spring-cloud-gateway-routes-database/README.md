# spring-cloud-gateway-routes-database

Stores route definitions in a relational database (R2DBC) and aggregates them into the route
locator. Routes are managed through a REST API, and through the view the
[console](../../spring-cloud-gateway-ui/README.md) renders over them.

Unlike the other sources, this one is not cached: it reads on demand, so it always reflects
the latest state.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-database</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.routes-database`.

```yaml
spring.cloud.gateway.server.webflux.routes-database:
  # Unwires the plugin entirely: no locator, no services, no endpoints.
  enabled: true
  # What is published over HTTP — never where the routes come from.
  access: read-only
  # Set to false to keep the plugin from contributing its own security chain.
  security-chain-enabled: true
```

| Property | Default | What it does |
| --- | --- | --- |
| `...routes-database.enabled` | `true` | Is the plugin there at all; `false` stops the database feeding the gateway |
| `...routes-database.access` | `unrestricted` | What is published: `unrestricted`, `read-only`, `none` |
| `...routes-database.security-chain-enabled` | `true` | Whether the plugin contributes its own `SecurityWebFilterChain` |

Whatever `access` is set to, **the database keeps feeding the gateway its routes**. It governs
what is published over HTTP, not where the routes come from.

| `access` | Effect |
| --- | --- |
| `unrestricted` | Everything: read and write |
| `read-only` | Routes can be listed and read; anything that would change one answers `405 Method Not Allowed`, and the view drops the buttons leading there |
| `none` | No endpoint at all: the controllers are not registered and the view does not appear |

`read-only` is the answer available to a gateway that has no authentication to offer: routes
arriving by migration can be published for reading with every change refused, without
authenticating anybody.

## REST API

A reactive API under `/api/gateway/routes`: CRUD on route definitions at runtime, each route
carrying its predicates and its filters in the same form the gateway configuration uses.

## Management view

The page over these routes belongs to the
[console](../../spring-cloud-gateway-ui/README.md#database-routes-view), served at
`/ui/routes/db`: this plugin holds the routes, the console renders them. Add
`spring-cloud-gateway-ui` and the view lights up with a **Database routes** entry in the side
menu; without it, this plugin exposes its REST API alone.

![The database routes view, inside the gateway UI shell](../../spring-cloud-gateway-ui/doc/routes-db-light.png)

## Who may change the routes

Reaching this API, or that page, reconfigures the routing table. When Spring Security is
active, the plugin contributes its own `SecurityWebFilterChain` asking for an authenticated
principal on its API — HTTP Basic against whatever user directory the application declared,
and Bearer tokens when it configured an issuer. CSRF is off on that chain, since everything it
matches is called with a token and a JSON body by a client holding no session.

Two conditions keep this plugin from making a gateway *less* safe by being added to it:

* **It backs off when no other `SecurityWebFilterChain` exists.** Spring Security serves its
  own "everything authenticated" chain as long as there is none, and that chain already covers
  these paths. Replacing it with one matching route management alone would leave every other
  path unguarded.
* **It backs off when there is nothing to authenticate against** — no user directory, no
  authentication manager, no issuer. Such a gateway closes these endpoints with `access`
  instead.

The chain is ordered at `GatewayDatabaseSecurityAutoConfiguration.ROUTE_MANAGEMENT_CHAIN_ORDER`
(`Ordered.HIGHEST_PRECEDENCE + 350`), behind the one the console contributes. Two escape
hatches: declare your own bean named `routeManagementSecurityWebFilterChain`, or set
`security-chain-enabled: false`.

**With the console.** The module declares its API through a `SecuredPaths.api(...)` bean from
[`spring-cloud-gateway-commons`](../../spring-cloud-gateway-commons/README.md), as endpoints
that change the gateway — so the console asks for a principal on them whether or not it is
itself open, under `...ui.security.write-mode`. Its chain is ordered ahead of the one above,
so a gateway carrying the console has a single place deciding who reaches what. See
[the console's README](../../spring-cloud-gateway-ui/README.md#the-endpoints-that-change-the-gateway).

## Sample

[gateway-routes-all](../../spring-cloud-gateway-samples/gateway/gateway-routes-all/README.md)
— port `8210`, with PostgreSQL on `15432`.
