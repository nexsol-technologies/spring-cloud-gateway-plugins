# spring-cloud-gateway-samples

Runnable applications exercising the plugins. Each gateway sample runs **one plugin**, so its
configuration reads as that plugin and nothing else; three combinations then show what the
plugins do together.

## The gateways

Pick the one matching what you want to see, start whatever supporting module it names, and
run it with `mvn spring-boot:run` from its directory.

### One plugin each

| Sample | Port | Plugin | Also needs |
| --- | --- | --- | --- |
| [gateway-filters](gateway/gateway-filters/README.md) | 8201 | [filters](../spring-cloud-gateway-filters/README.md) — `Authorization`, `ConvertHttpMethod`, `CorrelationId` | `service-a` |
| [gateway-oauth2](gateway/gateway-oauth2/README.md) | 8202 | [oauth2](../spring-cloud-gateway-oauth2/README.md) — `AuthorizationToken`, Basic-to-Bearer, multitenancy | `auth-server` |
| [gateway-hub-openapi](gateway/gateway-hub-openapi/README.md) | 8203 | [hub-openapi](../spring-cloud-gateway-hub-openapi/README.md) — the aggregated Swagger UI | `eureka`, `service-a` |
| [gateway-ui](gateway/gateway-ui/README.md) | 8204 | [ui](../spring-cloud-gateway-ui/README.md) — the shell, alone | — |
| [gateway-audit](gateway/gateway-audit/README.md) | 8205 | [audit](../spring-cloud-gateway-audit/README.md) — and its Redis, Kafka and R2DBC providers | `docker compose` |
| [gateway-metrics](gateway/gateway-metrics/README.md) | 8206 | [metrics](../spring-cloud-gateway-metrics/README.md) — and its Prometheus, Redis and discovery sources | `docker compose`, `eureka` |
| [gateway-routes-files](gateway/gateway-routes-files/README.md) | 8207 | [routes-files](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-files/README.md) — routes as files | — |

### Combinations

| Sample | Port | What it combines |
| --- | --- | --- |
| [gateway-routes-all](gateway/gateway-routes-all/README.md) | 8210 | every [route source](../spring-cloud-gateway-routes/README.md) at once — properties, files, database, Config Server, OpenAPI — plus `routes-security` |
| [gateway-secured](gateway/gateway-secured/README.md) | 8211 | `oauth2` + `filters` + `routes-security`: the three moments a secured gateway decides at |
| [gateway-full](gateway/gateway-full/README.md) | 8181 | every plugin, the integrated demo |

The ports never collide, so several gateways can run side by side.

## The supporting modules

| Module | Port | What it is |
| --- | --- | --- |
| [service-a](service-a) | 8080 | the downstream backend, exposing one controller and its OpenAPI contract |
| [eureka](eureka) | 8761 | the service registry |
| [config-server](config-server) | 8888 | a Config Server serving route files from a classpath repository |
| [auth-server](auth-server) | 9090 | an OAuth2 authorization server; accounts `user:user` (role `READ`) and `admin:admin` (role `ADMIN`), client `messaging-client:secret` |

Check a file served by the Config Server with:

```console
curl http://localhost:8888/gateway/default/main/orders-routes.yaml
```

## Infrastructure

The samples needing a backend ship their own `docker-compose.yml`, next to the module:

| Sample | Services | Ports |
| --- | --- | --- |
| [gateway-audit](gateway/gateway-audit/docker-compose.yml) | Redis, Kafka, PostgreSQL | 6379, 9092, 15433 |
| [gateway-metrics](gateway/gateway-metrics/docker-compose.yml) | Redis, Prometheus | 6379, 9091 |
| [gateway-routes-all](gateway/gateway-routes-all/docker-compose.yml) | PostgreSQL | 15432 |

Start only the service the profile you are running needs — `docker compose up -d redis`
rather than `docker compose up -d`. Every sample has a default profile needing none of them.

## Building

```console
mvn -DskipTests install     # from the repository root
```

The samples resolve the plugins from the reactor, so they build from the root rather than on
their own.
