# spring-cloud-gateway-openapi

This plugin provides openapi support for Spring Cloud Gateway

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol-tech.gateway</groupId>
           <artifactId>spring-cloud-gateway-openapi</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## Using Discovery client

If Spring Cloud Gateway use route locator with discovery client (like eureka), this plugin search for openapi documentation in down stream client (with default path `/v3/api-docs`).

```yaml
spring.cloud.gateway.server.webflux:
  discovery:
    locator:
      enabled: true
```
