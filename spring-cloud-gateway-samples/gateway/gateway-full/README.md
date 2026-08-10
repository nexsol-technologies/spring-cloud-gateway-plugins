# gateway-full

**A combination.** Every plugin at once, on port `8181` — the integrated demo, and the one
that answers *do these coexist*.

For learning one plugin, prefer the focused samples: they run alone, and their configuration
is about one thing. This one is deliberately dense.

## Running it

```console
mvn spring-boot:run
```

Started bare it answers, with the routes and views that need no backend. The scenarios below
each name what they additionally need.

## What is wired in

| Plugin | Where to look |
| --- | --- |
| `filters` | the `test-authorization*` routes |
| `oauth2` | the `AuthorizationToken` routes, the multi-tenant resource server |
| `routes-files` | [`gateway-routes/extra-routes.yaml`](src/main/resources/gateway-routes/extra-routes.yaml) |
| `routes-openapi` | the petstore source, one route per operation |
| `routes-configserver` | the `configserver` profile |
| `routes-database` | `/ui/routes/db` |
| `routes-security` | on the classpath; this gateway permits everything, so nothing here needs exempting — see [gateway-secured](../gateway-secured/README.md) |
| `hub-openapi` | `/swagger-ui.html`, under the `eureka` profile |
| `metrics` | `/ui/metrics` and `/ui/metrics/instances`, both instrumentation switches on |
| `audit` | `/ui/audit`, with the global web filter on |
| `ui` | `/ui` |

## Scenarios

### Filters and OAuth2

| Url | What it shows |
| --- | --- |
| http://localhost:8181/test-authorization/sample | Basic authentication validated, then the `ROLE_READ` authority checked |
| http://localhost:8181/test-authorization-token/sample | a JWT passing the `AuthorizationToken` rules |
| http://localhost:8181/test-authorization-token-ko/sample | the same JWT rejected |

The token routes need the [`auth-server`](../../auth-server) sample on `:9090`, and the
backend is [`service-a`](../../service-a) on `:8080`.

### The UI

http://localhost:8181/ui. Every view lights up here, since every plugin is present: the home
page, the routes with their sources, the route tester, the traffic chart, the instances
view, the audit tail and the **Database routes** management page.

The console carries its own login page: sign in with `superadmin` / `superadmin`, or set
`ADMIN_PASSWORD`. That is the whole configuration &mdash;
[application.yml](src/main/resources/application.yml) sets
`spring.cloud.gateway.server.webflux.ui.security.mode` to `authenticated` and names a local
user; the plugin contributes the chain.

Two things are worth noticing while you are in there. The side menu gains the operator and
the button that ends the session, next to the theme switch. And **Database routes** is the
one page of the console the plugin leaves to the application &mdash; it belongs to another
plugin, and it creates and deletes routes &mdash; so
[ApiGatewayApplication](src/main/java/ch/nexsol/gateway/sample/ApiGatewayApplication.java)
asks for a principal on it and sends visitors to the same login page. Signing in once opens
both.

This sample is also where the two resource servers of a gateway sit side by side, which is
worth a look because they are easy to confuse:

| | Property | Here |
| --- | --- | --- |
| The traffic being routed | `spring.security.oauth2.resourceserver` | the multi-tenant issuers, further down `application.yml` |
| The endpoints of the console | `spring.cloud.gateway.server.webflux.ui.security.oauth2.resourceserver` | its own issuer |

They happen to name the same authorization server here, and writing it twice is the point:
the console can be pointed at another provider in one line without touching what the routes
depend on. The Spring property holds a single issuer for the whole application, so putting
the console's issuer there would replace the one the routes validate against.

```console
$ TOKEN=$(curl -s -u messaging-client:secret \
    -d grant_type=client_credentials \
    http://localhost:9090/oauth2/token | jq -r .access_token)

$ curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
    http://localhost:8181/ui/routes/list
200
```

Without the token that call answers `302` to the login page; with a token the console cannot
decode it answers `401` and `WWW-Authenticate: Bearer`, never an HTML page. The keys are
fetched from the issuer on the first token that arrives, so the gateway starts whether or
not `auth-server` is up.

The [gateway-ui-secured](../gateway-ui-secured/README.md) sample is the other half of this:
the same login page, with Keycloak and roles behind it.

### OpenAPI hub

> Run the gateway and `service-a` with the `eureka` profile, and the `eureka` sample.

http://localhost:8181/swagger-ui.html serves the contracts of the discovered services,
`SERVICE-A` among them, next to the statically configured petstore source.

<p align="center">
  <img src="../../doc/spring-cloud-gateway-openapi.png" alt="spring-cloud-gateway-openapi" width="50%"/>
</p>

### Config Server routes

> Start the [`config-server`](../../config-server) sample, then this gateway with the
> `configserver` profile: `mvn spring-boot:run -Dspring-boot.run.profiles=configserver`.

| Url | Route |
| --- | --- |
| http://localhost:8181/cs-orders/get | `configserver_orders_route` → httpbin.org |
| http://localhost:8181/cs-billing/get | `configserver_billing_route` → httpbin.org |

Change a file under `config-server/.../config-repo/` and call
`POST http://localhost:8181/actuator/refresh`, or wait for `update-interval`.

### PostgreSQL

The database routes run on an in-memory H2 by default. `-Dspring-boot.run.profiles=pgsql`
points them at a PostgreSQL on `:15432` — the
[gateway-routes-all](../gateway-routes-all/README.md) sample ships a `docker-compose.yml`
starting one on that port.

## Auditing and metrics here

Both run with **no provider**: the audit events are logged and republished as Spring
application events, and the figures are those of this instance. The
[gateway-audit](../gateway-audit/README.md) and
[gateway-metrics](../gateway-metrics/README.md) samples are where the Redis, Kafka, R2DBC,
Prometheus and discovery backends are exercised.

The metrics plugin serves two views here. http://localhost:8181/ui/metrics answers *which
route carries the load*; http://localhost:8181/ui/metrics/instances answers *which instance
is in trouble* — heap, processor, threads, and the connection pools towards the downstream
services.

Both instrumentation switches are on, which is what makes the pool and event loop sections
exist at all:

```yaml
spring.cloud.gateway.server.webflux.httpclient.pool.metrics: true
spring.cloud.gateway.server.webflux.metrics.instance.instrument-http-client: true
```

Neither is on by default — they add a metrics recorder to the pipeline of every connection,
so they cost something on the data path. The pool rows appear once a downstream has actually
been called, so send some traffic through first.

## Tracing

This sample traces with Brave, which since Spring Boot 4 takes two dependencies: the bridge
`micrometer-tracing-bridge-brave` **and** the auto-configuration module
`spring-boot-micrometer-tracing-brave`. Without the second one nothing wires a `Tracer`, and
the trace id silently comes out empty everywhere &mdash; no `x-correlation-id` on the
responses, empty `trace.id` in the audit events, empty `traceId` in the logs. See
[wiring a real tracer](../../../spring-cloud-gateway-filters/README.md#wiring-a-real-tracer)
for that pair and its OpenTelemetry equivalent.
