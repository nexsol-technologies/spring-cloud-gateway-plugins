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
| `metrics` | `/ui/metrics` and `/ui/metrics/instances`, both instrumentation switches on, the consolidating sources behind the `metrics-*` profiles |
| `service-graph` | the `service-a` and `service-b` routes, and the two flows [below](#who-calls-what) |
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

The profile also sets `hub-openapi.security.issuer: gateway`, which is worth a look on this
sample because it is where the two tenants are declared. A service names, in its contract,
the issuer it validates its own traffic against — an address internal to the cluster, which
resolves to nothing in the browser reading the contract. This advertises the issuers of the
gateway instead, read straight from `spring.security.oauth2.resourceserver.multitenant`:
`local` and `local2` become two schemes and two alternatives, so the page offers them as the
choice they are. Set it back to `document` and each contract keeps the issuer its service
wrote.

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

Auditing runs with **no provider**: the events are logged and republished as Spring
application events. The [gateway-audit](../gateway-audit/README.md) sample is where the
Redis, Kafka and R2DBC backends are exercised.

Metrics start the same way — the figures are those of this instance — and the three
consolidating sources sit behind a profile each, [below](#consolidating-the-metrics).

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

### Consolidating the metrics

With no profile the views report what **this JVM** counted, and say so under the chart:
`this instance only`. Behind a load balancer that is a share of the traffic, not the
traffic — which is what the three profiles below fix, each in its own way.

| | Default | `metrics-prometheus` | `metrics-redis` | `metrics-discovery` |
|---|---|---|---|---|
| Extra infrastructure | none | a Prometheus | a Redis | a service registry |
| Covers every instance | no | yes | yes | yes |
| Survives a restart | no | **yes** | no | no |
| Freshness | live | scrape interval | publish interval | live |

[`docker-compose.yml`](docker-compose.yml) starts the first two. It binds the same host
ports as the `gateway-metrics` sample, so run one sample or the other.

#### Redis

```console
docker compose up -d redis
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-redis
```

Each instance writes its own key and never touches the others', which is what lets them all
publish without any locking. Start a second one — that is the `instance2` profile, see
[below](#a-second-instance) — to watch the figures add up:

```console
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-redis,instance2
```

The coverage then reads `2 instances, via Redis`. Stop one and it fades out on its own when
its key expires — `time-to-live` is 45s against a 10s publish interval.

#### Prometheus

```console
docker compose up -d prometheus
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-prometheus
```

Prometheus is on http://localhost:9091 (not `:9090`, which the `auth-server` sample uses) and
scrapes `/actuator/prometheus` on the host every 5 seconds — see
[`prometheus.yml`](prometheus.yml). Give it one scrape interval before the figures appear.
The endpoint itself comes from `micrometer-registry-prometheus`, which this sample carries
for that reason alone.

The `selector: job="gateway-full"` matters: a shared Prometheus otherwise mixes the traffic
of every gateway publishing the same meter into one figure, wrong in a way nothing on the
page would reveal.

#### Discovery

```console
# from spring-cloud-gateway-samples/eureka
mvn spring-boot:run
# here, twice
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-discovery
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-discovery,instance2
```

No infrastructure beyond the registry: each instance is polled directly on
`/ui/metrics/local`, which answers with **its own** figures and never the consolidated ones —
the base case that keeps the instances from polling each other forever. An instance that does
not answer is left out, and the coverage says so.

That endpoint belongs to the metrics plugin, not to the console, so the chain the `ui` plugin
contributes does not cover it and the `/ui/**` rule of this application would close it. It is
permitted in
[ApiGatewayApplication](src/main/java/ch/nexsol/gateway/sample/ApiGatewayApplication.java);
without that, every instance answers its siblings a redirect to the login page and reports
only its own traffic. It exists only while the discovery provider is selected.

The profile turns the Eureka client on by itself, so it needs nothing else. Combine it with
the `eureka` profile to get the discovery route locator and the OpenAPI hub as well:
`-Dspring-boot.run.profiles=eureka,metrics-discovery`.

#### A second instance

[`application-instance2.yml`](src/main/resources/application-instance2.yml) is the second
instance: port `8191`, `instance-id` `gateway-full-2`, nothing else. Add it to whichever
source is being tried, last, so it keeps the port and the identity whatever the other
profile sets:

```console
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-redis,instance2
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-prometheus,instance2
mvn spring-boot:run -Dspring-boot.run.profiles=metrics-discovery,instance2
```

Redis and discovery need nothing more from it. Prometheus does: uncomment the `8191` target
in [`prometheus.yml`](prometheus.yml) and restart the container, or it is never scraped and
the second instance stays invisible — a scraper is told what to visit, where the other two
sources are told by the instance itself.

The two share what lives outside the JVM — the Redis keys, the Eureka registration — and
nothing else: the database routes run on a private in-memory H2, so each has its own. Run
both under `pgsql` to share those too.

## Who calls what

The `service-a` and `service-b` routes exist to make the
[service graph](../../../spring-cloud-gateway-service-graph/README.md) show something real.
Start the two backends, then this gateway:

```console
# from spring-cloud-gateway-samples/service-b, then service-a
mvn spring-boot:run
```

```console
curl -H 'X-Caller: frontend' localhost:8181/service-a/call-through-gateway
curl -H 'X-Caller: frontend' localhost:8181/service-a/call-direct
```

Both endpoints of `service-a` call `service-b`. The first goes through this gateway, which
counts the hop and draws `service-a -> service-b`; the second goes straight to port 8081,
and no counter here can know it happened. That difference is what separates the three
sources counting this gateway's traffic from the one reading a tracing backend.

```console
curl -s localhost:8181/actuator/metrics/gateway.service.graph.calls | jq
```

Two things to notice while reading it:

* **The far end is named after what the route targets.** `localhost:8080` in the default
  profile, `service-a` under the `eureka` one, where the routes go through the load
  balancer. That is `targetService` reading the route URI, not a name the plugin invents.
* **The caller comes from a header here**, because these samples carry no token —
  `service-graph.caller.header: X-Caller`, and `service-a` names itself when it calls. A
  deployment where the services present their own tokens needs none of it: `azp` names the
  caller and the claims are read first. A header is chosen by whoever calls, and is trusted
  here only because everything is on one machine.
* **The documentation routes are absent**, and deliberately: `excluded-routes` leaves out
  `openapi-docs-.*`, since fetching a contract is not one service calling another.

## Turning the plugins off

Every plugin here is configuration, not code, and the `plugins-off` profile is the proof:

```console
mvn spring-boot:run -Dspring-boot.run.profiles=plugins-off
```

Same jar, same classpath, and a gateway that routes traffic and does nothing else — no
console, no audit trail, no metrics, no route source beyond `application.yml`. Useful to
weigh what a plugin costs, and to check that none of them is load-bearing.

Most of them carry their own switch:

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

all under `spring.cloud.gateway.server.webflux`. Two cases differ:

- **`openapi-validation`** has no `enabled` flag because it is attached route by route.
  Setting `openapi-validation.request.mode` and `response.mode` to `OFF` neutralises it
  wherever a route attached it. Note the quotes in the YAML — unquoted, `OFF` is read as
  the boolean `false`.
- **`ui` and `routes-database`** have no switch at all: they are wired on the strength of
  being on the classpath. Boot's own `spring.autoconfigure.exclude` is what turns them off,
  and it is a different thing — it silences the auto-configuration, so `/ui` answers `404`
  rather than answering that it is disabled.

The filter factories the plugins contribute (`Authorization`, `AuthorizationToken`,
`OpenapiValidation`, …) stay registered under this profile and have nothing to turn off: a
filter factory is only ever applied by a route naming it.

## Tracing

This sample traces with Brave, which since Spring Boot 4 takes two dependencies: the bridge
`micrometer-tracing-bridge-brave` **and** the auto-configuration module
`spring-boot-micrometer-tracing-brave`. Without the second one nothing wires a `Tracer`, and
the trace id silently comes out empty everywhere &mdash; no `x-correlation-id` on the
responses, empty `trace.id` in the audit events, empty `traceId` in the logs. See
[wiring a real tracer](../../../spring-cloud-gateway-filters/README.md#wiring-a-real-tracer)
for that pair and its OpenTelemetry equivalent.
