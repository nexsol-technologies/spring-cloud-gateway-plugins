# spring-cloud-gateway-samples

Runnable applications exercising the plugins. Each gateway sample runs **one plugin**, so its
configuration reads as that plugin and nothing else; four combinations then show what the
plugins do together.

## Build first

```console
mvn -DskipTests install     # from the repository root
```

The samples resolve the plugins from the reactor, so they build from the root rather than on
their own. Then run one with `mvn spring-boot:run` from its directory. The ports never collide,
so several gateways can run side by side.

## The gateways

### One plugin each

| Sample | Port | Plugin | Also needs |
| --- | --- | --- | --- |
| [gateway-filters](gateway/gateway-filters/README.md) | 8201 | [filters](../spring-cloud-gateway-filters/README.md) — `Authorization`, `ConvertHttpMethod`, `CorrelationId` | `service-a` |
| [gateway-oauth2](gateway/gateway-oauth2/README.md) | 8202 | [oauth2](../spring-cloud-gateway-oauth2/README.md) — `AuthorizationToken`, Basic-to-Bearer, multitenancy | `auth-server` |
| [gateway-hub-openapi](gateway/gateway-hub-openapi/README.md) | 8203 | [hub-openapi](../spring-cloud-gateway-hub-openapi/README.md) — the aggregated Swagger UI | `eureka`, `service-a` |
| [gateway-ui](gateway/gateway-ui/README.md) | 8204 | [ui](../spring-cloud-gateway-ui/README.md) — the console, alone | — |
| [gateway-audit](gateway/gateway-audit/README.md) | 8205 | [audit](../spring-cloud-gateway-audit/README.md) — and its Redis, Kafka and R2DBC providers | `docker compose` |
| [gateway-metrics](gateway/gateway-metrics/README.md) | 8206 | [metrics](../spring-cloud-gateway-metrics/README.md) — and its Prometheus, Redis and discovery sources | `docker compose`, `eureka` |
| [gateway-routes-files](gateway/gateway-routes-files/README.md) | 8207 | [routes-files](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-files/README.md) — routes as files | — |
| [gateway-openapi-validation](gateway/gateway-openapi-validation/README.md) | 8212 | [openapi-validation](../spring-cloud-gateway-openapi-validation/README.md) — requests enforced against a contract | — |

### Combinations

| Sample | Port | What it combines |
| --- | --- | --- |
| [gateway-routes-all](gateway/gateway-routes-all/README.md) | 8210 | Every [route source](../spring-cloud-gateway-routes/README.md) at once — properties, files, database, Config Server, OpenAPI — plus `routes-security` |
| [gateway-secured](gateway/gateway-secured/README.md) | 8211 | `oauth2` + `filters` + `routes-security`: the three moments a secured gateway decides at |
| [gateway-ui-secured](gateway/gateway-ui-secured/README.md) | 8213 | `ui` behind its own login page: a local user, Keycloak, and a Bearer token on its endpoints |
| [gateway-full](gateway/gateway-full/README.md) | 8181 | Every plugin, the integrated demo |

## The supporting modules

| Module | Port | What it is |
| --- | --- | --- |
| [service-a](service-a) | 8080 | The downstream backend: one controller, its OpenAPI contract, and the two flows towards `service-b` |
| [service-b](service-b) | 8081 | A second backend, called by `service-a` — directly, and through the gateway |
| [eureka](eureka) | 8761 | The service registry |
| [config-server](config-server) | 8888 | A Config Server serving route files from a classpath repository |
| [auth-server](auth-server) | 9090 | An OAuth2 authorization server; accounts `user:user` (role `READ`) and `admin:admin` (role `ADMIN`), client `messaging-client:secret` |

## Infrastructure

The samples needing a backend ship their own `docker-compose.yml`, next to the module. Start
only the service the profile you are running needs — `docker compose up -d redis` rather than
`docker compose up -d`. Every sample has a default profile needing none of them.

| Sample | Services | Ports |
| --- | --- | --- |
| [gateway-audit](gateway/gateway-audit/docker-compose.yml) | Redis, Kafka, PostgreSQL | 6379, 9092, 15433 |
| [gateway-metrics](gateway/gateway-metrics/docker-compose.yml) | Redis, Prometheus | 6379, 9091 |
| [gateway-routes-all](gateway/gateway-routes-all/docker-compose.yml) | PostgreSQL | 15432 |
| [gateway-ui-secured](gateway/gateway-ui-secured/docker-compose.yml) | Keycloak, realm imported at start-up | 8380 |

## The two flows towards service-b

`service-a` reaches `service-b` twice, and the difference is the whole point of the
[service graph](../spring-cloud-gateway-service-graph/README.md):

```console
curl -H 'X-Caller: frontend' localhost:8181/service-a/call-through-gateway
curl -H 'X-Caller: frontend' localhost:8181/service-a/call-direct
```

The first draws two edges — `frontend -> service-a` and `service-a -> service-b` — because both
hops transited the gateway. The second draws only one: its second hop went straight from
`service-a` to `service-b` on port 8081, and no counter of the gateway can know about a call it
never carried. Only a source reading a tracing backend can.

Run `service-b` and `service-a` (in that order), then `gateway-full`. Either run all three
without a profile, or all three with `eureka` — a gateway on the `eureka` profile routes to
`lb://service-b`, and a backend that did not register leaves the load balancer with no instance
and the gateway answering `503`. Then read the graph the gateway holds:

```console
curl localhost:8181/actuator/metrics/gateway.service.graph.calls
```

The far end of an edge is named after what the route targets, so it reads `localhost:8080` in
the default profile and `service-a` under `eureka`, where the routes go through the load
balancer.
