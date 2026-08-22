# gateway-full

**A combination** — port `8181`: every plugin at once, the integrated demo, and the one that
answers *do these coexist*.

For learning one plugin, prefer the focused samples: they run alone, and their configuration
is about one thing. This one is deliberately dense.

## Run it

```console
mvn spring-boot:run
```

Started bare it answers, with the routes and views that need no backend. The scenarios below
each name what they additionally need.

## What is wired in

| Plugin | Where to look |
| --- | --- |
| `filters` | The `test-authorization*` routes |
| `oauth2` | The `AuthorizationToken` routes, the multi-tenant resource server |
| `routes-files` | [`gateway-routes/extra-routes.yaml`](src/main/resources/gateway-routes/extra-routes.yaml) |
| `routes-openapi` | The petstore source, one route per operation |
| `routes-configserver` | The `configserver` profile |
| `routes-database` | `/ui/routes/db` |
| `routes-security` | On the classpath; this gateway permits everything, so nothing here needs exempting — see [gateway-secured](../gateway-secured/README.md) |
| `hub-openapi` | `/swagger-ui.html` and `/ui/openapi`, under the `eureka` profile |
| `metrics` | `/ui/metrics` and `/ui/metrics/instances`, both instrumentation switches on |
| `service-graph` | The `service-a` and `service-b` routes, and the two flows [below](#who-calls-what) |
| `audit` | `/ui/audit`, with the global web filter on |
| `ui` | `/ui` |

## Profiles

| Profile | Needs | What it adds |
| --- | --- | --- |
| _(none)_ | — | Everything that needs no backend; H2 for the database routes |
| `eureka` | The `eureka` sample | The discovery route locator and the OpenAPI hub |
| `configserver` | The `config-server` sample | Two more routes, read from the files it serves |
| `pgsql` | PostgreSQL on `:15432` | The database routes on PostgreSQL instead of H2 |
| `metrics-redis` | `docker compose up -d redis` | Metrics consolidated through Redis |
| `metrics-prometheus` | `docker compose up -d prometheus` | Metrics consolidated through Prometheus, on `:9091` |
| `metrics-discovery` | The `eureka` sample | Metrics consolidated by polling every registered instance |
| `instance2` | — | A second instance on `8191`, `instance-id` `gateway-full-2` |
| `plugins-off` | — | Same jar, every plugin switched off |

## Scenarios

### Filters and OAuth2

| Url | What it shows |
| --- | --- |
| http://localhost:8181/test-authorization/sample | Basic authentication validated, then the `ROLE_READ` authority checked |
| http://localhost:8181/test-authorization-token/sample | A JWT passing the `AuthorizationToken` rules |
| http://localhost:8181/test-authorization-token-ko/sample | The same JWT rejected |

The token routes need the [`auth-server`](../../auth-server) sample on `:9090`, and the backend
is [`service-a`](../../service-a) on `:8080`.

### The console

http://localhost:8181/ui. Every view lights up here, since every plugin is present. The console
carries its own login page: sign in with `superadmin` / `superadmin`, or set `ADMIN_PASSWORD`.
That is the whole configuration — [application.yml](src/main/resources/application.yml) sets
`...ui.security.mode` to `authenticated` and names a local user; the plugin contributes the
chain.

**Database routes** is the one page of the console the plugin leaves to the application — it
belongs to another plugin, and it creates and deletes routes — so
[ApiGatewayApplication](src/main/java/ch/nexsol/gateway/sample/ApiGatewayApplication.java) asks
for a principal on it and sends visitors to the same login page.

This sample is also where the **two resource servers** of a gateway sit side by side, which is
worth a look because they are easy to confuse:

| | Property | Here |
| --- | --- | --- |
| The traffic being routed | `spring.security.oauth2.resourceserver` | The multi-tenant issuers, further down `application.yml` |
| The endpoints of the console | `...ui.security.oauth2.resourceserver` | Its own issuer |

They happen to name the same authorization server, and writing it twice is the point: the
console can be pointed at another provider in one line without touching what the routes depend
on. The Spring property holds a single issuer for the whole application.

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
fetched from the issuer on the first token, so the gateway starts whether or not `auth-server`
is up.

[gateway-ui-secured](../gateway-ui-secured/README.md) is the other half of this: the same login
page, with Keycloak and roles behind it.

### OpenAPI hub

> Run the gateway, `service-a` and `service-c` with the `eureka` profile, and the `eureka`
> sample.

http://localhost:8181/swagger-ui.html and http://localhost:8181/ui/openapi serve the contracts
of the discovered services, `SERVICE-A` and `SERVICE-C` among them, next to the statically
configured petstore source.

The profile also sets `hub-openapi.security.issuer: gateway`, and
[`service-c`](../../service-c) is the contract to read it on: it declares one `openIdConnect`
scheme named `bearer-oidc`, pointing at the issuer it validates its own traffic against.

```console
$ curl -s localhost:8181/v3/api-docs/SERVICE-C     # sign in first, the console is authenticated
security:
- bearer-oidc-local:  ["openid", "profile", "email"]
- bearer-oidc-local2: ["openid", "profile", "email"]
- bearer-oidc-jwt:    ["openid", "profile", "email"]
```

The scheme becomes one scheme per issuer and its requirement one alternative per issuer. Three
and not two: this `application.yml` declares `resourceserver.multitenant` **and**
`resourceserver.jwt.issuer-uri`, and every issuer found is advertised — the tenants under their
ids, the single issuer under `jwt`.

The scopes come from the requirement; an `openIdConnect` scheme declares none of its own. The
console shows only those the issuer advertises in `scopes_supported`, and the
[`auth-server`](../../auth-server) sample advertises `openid` alone — Keycloak would show the
three.

<p align="center">
  <img src="../../doc/spring-cloud-gateway-openapi.png" alt="spring-cloud-gateway-openapi" width="50%"/>
</p>

### Config Server routes

> Start the [`config-server`](../../config-server) sample, then this gateway with
> `-Dspring-boot.run.profiles=configserver`.

| Url | Route |
| --- | --- |
| http://localhost:8181/cs-orders/get | `configserver_orders_route` → httpbin.org |
| http://localhost:8181/cs-billing/get | `configserver_billing_route` → httpbin.org |

Change a file under `config-server/.../config-repo/` and call
`POST http://localhost:8181/actuator/refresh`, or wait for `update-interval`.

### Auditing and metrics

Auditing runs with **no provider**: the events are logged and republished as Spring application
events. [gateway-audit](../gateway-audit/README.md) is where the Redis, Kafka and R2DBC
backends are exercised.

Metrics start the same way — the figures are those of this instance, and the views say so under
the chart: `this instance only`. The three consolidating sources sit behind the `metrics-*`
profiles above; [gateway-metrics](../gateway-metrics/README.md) compares them.

Both instrumentation switches are on here, which is what makes the pool and event loop sections
of http://localhost:8181/ui/metrics/instances exist at all:

```yaml
spring.cloud.gateway.server.webflux.httpclient.pool.metrics: true
spring.cloud.gateway.server.webflux.metrics.instance.instrument-http-client: true
```

Neither is on by default — they add a metrics recorder to the pipeline of every connection. The
pool rows appear once a downstream has actually been called, so send some traffic through first.

### Running two instances

Add `instance2` **last**, so it keeps the port and the identity whatever the other profile sets:

```console
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-redis,instance2
```

The coverage then reads `2 instances, via Redis`. Redis and discovery need nothing more;
Prometheus does — uncomment the `8191` target in [`prometheus.yml`](prometheus.yml) and restart
the container, or the second instance is never scraped and stays invisible.

The two share what lives outside the JVM — the Redis keys, the Eureka registration — and
nothing else: the database routes run on a private in-memory H2, so each has its own. Console
sessions are not shared either, which this sample never shows since each instance is browsed on
its own port — see
[Running more than one instance](../../../spring-cloud-gateway-ui/README.md#running-more-than-one-instance).

Under `metrics-discovery`, `/ui/metrics/local` belongs to the metrics plugin and not to the
console, so the `/ui/**` rule of this application would close it. It is permitted in
[ApiGatewayApplication](src/main/java/ch/nexsol/gateway/sample/ApiGatewayApplication.java);
without that, every instance answers its siblings a redirect to the login page.

## Who calls what

The `service-a` and `service-b` routes exist to make the
[service graph](../../../spring-cloud-gateway-service-graph/README.md) show something real.
Start the two backends (`service-b` first), then this gateway:

```console
curl -H 'X-Caller: frontend' localhost:8181/service-a/call-through-gateway
curl -H 'X-Caller: frontend' localhost:8181/service-a/call-direct
```

Both endpoints of `service-a` call `service-b`. The first goes through this gateway, which
counts the hop and draws `service-a -> service-b`; the second goes straight to port 8081, and
no counter here can know it happened. That difference is what separates the three sources
counting this gateway's traffic from the one reading a tracing backend.

```console
curl -s localhost:8181/actuator/metrics/gateway.service.graph.calls | jq
```

Three things to notice while reading it:

* **The far end is named after what the route targets** — `localhost:8080` in the default
  profile, `service-a` under `eureka`, where the routes go through the load balancer.
* **The caller comes from a header here**, because these samples carry no token
  (`service-graph.caller.header: X-Caller`, and `service-a` names itself when it calls). A
  deployment where the services present their own tokens needs none of it: `azp` names the
  caller. A header is chosen by whoever calls, and is trusted here only because everything is
  on one machine.
* **The documentation routes are absent**, deliberately: `excluded-routes` leaves out
  `openapi-docs-.*`, since fetching a contract is not one service calling another.

## Turning the plugins off

Every plugin here is configuration, not code, and the `plugins-off` profile is the proof: same
jar, same classpath, and a gateway that routes traffic and does nothing else. Useful to weigh
what a plugin costs, and to check that none of them is load-bearing.

Most carry their own switch, all under `spring.cloud.gateway.server.webflux`:

| Plugin | Property |
| --- | --- |
| `filters` | `webfilter.correlation-id.enabled` |
| `oauth2` | `webfilter.basicauth-exchange-oauth2.enabled` |
| `routes-files` | `routes-files.enabled` |
| `routes-openapi` | `routes-openapi.enabled` |
| `routes-configserver` | `routes-configserver.enabled` |
| `routes-security` | `routes-security.public-routes.enabled` |
| `hub-openapi` | `hub-openapi.enabled` |
| `audit` | `audit.enabled`, `audit.web-filter.enabled` |
| `metrics` | `metrics.enabled`, `metrics.instance.enabled` |

Two cases differ:

* **`openapi-validation`** has no `enabled` flag because it is attached route by route. Setting
  `openapi-validation.request.mode` and `response.mode` to `OFF` neutralises it wherever a route
  attached it. Note the quotes in the YAML — unquoted, `OFF` is read as the boolean `false`.
* **`ui` and `routes-database`** have no switch at all: they are wired on the strength of being
  on the classpath. Boot's own `spring.autoconfigure.exclude` is what turns them off, and it is
  a different thing — it silences the auto-configuration, so `/ui` answers `404` rather than
  answering that it is disabled.

The filter factories the plugins contribute (`Authorization`, `AuthorizationToken`,
`OpenapiValidation`, …) stay registered under this profile and have nothing to turn off: a
filter factory is only ever applied by a route naming it.

## Tracing

This sample traces with Brave, which since Spring Boot 4 takes two dependencies: the bridge
`micrometer-tracing-bridge-brave` **and** the auto-configuration module
`spring-boot-micrometer-tracing-brave`. Without the second, nothing wires a `Tracer` and the
trace id silently comes out empty everywhere — no `x-correlation-id` on the responses, empty
`trace.id` in the audit events, empty `traceId` in the logs. See
[wiring a real tracer](../../../spring-cloud-gateway-filters/README.md#wiring-a-real-tracer).
