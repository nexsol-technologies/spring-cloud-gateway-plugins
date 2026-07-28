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
| `metrics` | `/ui/metrics` |
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
page, the routes with their sources, the route tester, the traffic chart, the audit tail and
the **Database routes** management page.

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
application events, and the traffic figures are those of this instance. The
[gateway-audit](../gateway-audit/README.md) and
[gateway-metrics](../gateway-metrics/README.md) samples are where the Redis, Kafka, R2DBC,
Prometheus and discovery backends are exercised.

## Tracing

This sample traces with Brave, which since Spring Boot 4 takes two dependencies: the bridge
`micrometer-tracing-bridge-brave` **and** the auto-configuration module
`spring-boot-micrometer-tracing-brave`. Without the second one nothing wires a `Tracer`, and
the trace id silently comes out empty everywhere &mdash; no `x-correlation-id` on the
responses, empty `trace.id` in the audit events, empty `traceId` in the logs. See
[wiring a real tracer](../../../spring-cloud-gateway-filters/README.md#wiring-a-real-tracer)
for that pair and its OpenTelemetry equivalent.
