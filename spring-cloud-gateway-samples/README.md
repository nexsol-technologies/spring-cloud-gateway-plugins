# spring-cloud-gateway-samples

Runnable applications exercising the plugins. Start the modules a scenario needs, then the
`gateway` module.

## The modules

### auth-server

A minimally configured OAuth2 authorization server. Two accounts are declared: `user:user`
with the `USER` role, and `admin:admin` with the `ADMIN` role.

### eureka

The service registry, used to exercise the OpenAPI discovery of the hub against `service-a`.

### service-a

A backend application exposing a single controller, used as the downstream service.

### config-server

A Spring Cloud Config Server (port `8888`) serving gateway route files from a native classpath
repository (`config-repo/orders-routes.yaml`, `config-repo/billing-routes.yaml`). Check a
served file with:

```console
curl http://localhost:8888/gateway/default/main/orders-routes.yaml
```

### gateway

The gateway itself, on port `8181`, with the plugins wired in. The scenarios below run
against it.

## Scenarios

### spring-cloud-gateway-filters

`application.yml` declares a few routes exercising the filters the plugin provides:

| Url | What it shows |
| --- | --- |
| http://localhost:8181/test-authorization/sample | Basic authentication being validated |
| http://localhost:8181/test-authorization-token/sample | a JWT obtained with `user:user` passing validation |
| http://localhost:8181/test-authorization-token-ko/sample | the same JWT being rejected |

### spring-cloud-gateway-hub-openapi

> Run the gateway and `service-a` with the `eureka` profile.

Open http://localhost:8181/swagger-ui.html: the Swagger UI serves the contracts of the
discovered services, `SERVICE-A` among them.

<p align="center">
  <img src="doc/spring-cloud-gateway-openapi.png" alt="spring-cloud-gateway-openapi" width="50%"/>
</p>

### spring-cloud-gateway-ui

The sample bundles the `spring-cloud-gateway-ui` shell. Open http://localhost:8181/ui for the
home page and its collapsible side menu. The routes-database plugin being on the classpath, a
**Database routes** entry lights up on its own and leads to the management UI.

### spring-cloud-gateway-routes-configserver

> Start the `config-server` module first, then the gateway with the `configserver` profile:
> `mvn spring-boot:run -Dspring-boot.run.profiles=configserver` from the `gateway` module.

The gateway loads its route files from the Config Server (see
[`application-configserver.yml`](gateway/src/main/resources/application-configserver.yml)) and
exposes:

| Url | Route |
| --- | --- |
| http://localhost:8181/cs-orders/get | `configserver_orders_route` → httpbin.org |
| http://localhost:8181/cs-billing/get | `configserver_billing_route` → httpbin.org |

Change a file under `config-server/.../config-repo/` and call
`POST http://localhost:8181/actuator/refresh` (or wait for `update-interval`) to reload the
routes without restarting the gateway.

### spring-cloud-gateway-routes-database

Open http://localhost:8181/ui/routes/db, or the **Database routes** menu entry, to manage the
routes stored in the database.
