# spring-cloud-gateway-routes-files

Sources Spring Cloud Gateway route definitions from JSON and YAML files and aggregates them
into the route locator. Designed for GitOps: routes are declared as files that a CI/CD
pipeline can validate, review and deploy.

```xml
<dependency>
    <groupId>ch.nexsol.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-files</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

```yaml
spring.cloud.gateway.server.webflux.routes-files:
  enabled: true
  watch: true                         # reload when a watched file changes (file: locations only)
  locations:
    - classpath:gateway-routes/*.yaml
    - file:./config/routes/*.json
```

## File format

The file mirrors the standard `spring.cloud.gateway.server.webflux` route configuration.
It is either a top-level array of routes or an object with a `routes` array. Predicates
and filters accept both the shorthand string form and the object form.

```yaml
routes:
  - id: after_route
    uri: https://example.org
    order: 1
    predicates:
      - Cookie=mycookie,mycookievalue   # shorthand form
      - name: Path                      # object form
        args:
          pattern: /api/**
    filters:
      - AddRequestHeader=X-Request-Foo,Bar
      - name: Retry
        args:
          retries: "3"
    metadata:
      tier: gold
```
