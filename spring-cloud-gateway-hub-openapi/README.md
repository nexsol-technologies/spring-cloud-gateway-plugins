# spring-cloud-gateway-hub-openapi

This plugin aggregates the OpenAPI documentation of downstream services into a hub for Spring Cloud Gateway.

> To generate gateway routes from an OpenAPI contract instead, use
> [spring-cloud-gateway-routes-openapi](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md).

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol-tech.gateway</groupId>
           <artifactId>spring-cloud-gateway-hub-openapi</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## Using Discovery client

If Spring Cloud Gateway use route locator with discovery client (like eureka), this plugin search for openapi documentation in down stream client (with default path `/v3/api-docs`).

When the application has no discovery client, the discovery-based beans simply back off:
the hub keeps aggregating the statically configured contracts described below.

```yaml
spring.cloud.gateway.server.webflux:
  discovery:
    locator:
      enabled: true
```

## Aggregating statically configured OpenAPI contracts

When [spring-cloud-gateway-routes-openapi](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md)
is also on the classpath, the OpenAPI contracts it is configured with are automatically
exposed in the aggregated Swagger UI, side by side with the discovered services. Each
source's contract is proxied through the gateway (its `servers` section rewritten to the
gateway), so there is no CORS issue and "Try it out" targets the gateway.

No extra configuration is needed beyond enabling both plugins:

```yaml
spring.cloud.gateway.server.webflux.hub-openapi:
  enabled: true                               # enable the hub / Swagger UI aggregation
  gateway-uri: http://localhost:8181          # required by the hub to rewrite the servers
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources:
    - id: petstore
      uri: https://petstore3.swagger.io
      spec-url: https://petstore3.swagger.io/api/v3/openapi.json
      mode: PER_OPERATION
```

The source then appears in the Swagger UI dropdown as `petstore`, served through the
gateway at `/v3/api-docs/petstore`.
