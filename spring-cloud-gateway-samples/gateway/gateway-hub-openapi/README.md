# gateway-hub-openapi

Exercises [spring-cloud-gateway-hub-openapi](../../../spring-cloud-gateway-hub-openapi/README.md)
on port `8203`: the OpenAPI contracts of the downstream services, aggregated into a single
Swagger UI served by the gateway.

## Running it

```console
# from spring-cloud-gateway-samples/eureka
mvn spring-boot:run
# from spring-cloud-gateway-samples/service-a
mvn spring-boot:run -Dspring-boot.run.profiles=eureka
# from here
mvn spring-boot:run
```

## What it shows

Open http://localhost:8203/swagger-ui.html. The dropdown holds:

- **SERVICE-A**, discovered through Eureka — the hub probed the well-known SpringDoc paths
  and found its contract;
- **petstore**, a statically configured source, aggregated side by side with the discovered
  services.

Each contract is proxied through the gateway with its `servers` section rewritten to
`gateway-uri`, so **Try it out** calls the gateway rather than the backend, and raises no
CORS issue.

## The two halves

The hub aggregates *documentation*. It does not create routes. What puts the petstore
contract in the dropdown is
[spring-cloud-gateway-routes-openapi](../../../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md),
which turns that same contract into gateway routes — one per operation here, prefixed with
`/petstore`. The hub notices it is on the classpath and advertises its sources too.

Both are enabled in [`application.yml`](src/main/resources/application.yml); disable
`routes-openapi` and only the discovered services remain.

## Sizing the discovery

The gateway refreshes its routes on every discovery heartbeat, and each refresh probes the
services it does not know yet. On a registry holding hundreds of services, probing them all
at once saturates the connection pool and the gateway stops routing while the probes fail.
The `discovery.timeout`, `concurrency`, `max-connections` and `cache-ttl` settings keep that
cost constant; they are spelled out in the sample to show where the knobs are, not because
one registered service needs them.

A service can also declare where its document is and skip the probing altogether:

```yaml
eureka.instance.metadata-map.openapi_path: /v3/api-docs
```
