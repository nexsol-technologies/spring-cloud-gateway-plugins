# spring-cloud-gateway-routes-security

Lets a gateway route declare itself **public** so that requests targeting it bypass Spring
Security (Basic auth, OAuth 2.0). Works with every route source (database, files, OpenAPI):
a route is public as soon as its metadata carries a truthy `public` entry.

```xml
<dependency>
    <groupId>ch.nexsol.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-security</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Why a dedicated matcher

Spring Security runs **before** Spring Cloud Gateway resolves the matching route, so at
security-filter time the target route is not known yet. This module ships a
`PublicRouteMatcher` that replays the route predicates against the exchange (exactly as the
gateway does later) to resolve the target route up front, then matches when that route is
flagged public.

## How it works

`RoutesSecurityAutoConfiguration` registers a high-priority `SecurityWebFilterChain`
(ordered ahead of the application chains) whose security matcher is the `PublicRouteMatcher`.
Because Spring Security serves a request with the **first** chain whose matcher matches, a
public route is handled by this permissive chain and never reaches the application's
Basic-auth / OAuth 2.0 chains. Every other request falls through to those chains unchanged.

## Declaring a public route

Set `public: true` in the route metadata, whatever the source.

Files (JSON/YAML):

```yaml
routes:
  - id: public-api
    uri: https://backend.example.org
    predicates:
      - Path=/public/**
    metadata:
      public: true
```

OpenAPI source:

```yaml
spring.cloud.gateway.routes.openapi.sources[0].metadata.public: true
```

Database: tick **Public route** in the routes UI, or set the `public_route` column
(exposed as `publicRoute` in the REST API).

## Configuration

The feature is active as soon as the module is on the classpath. Disable it with:

```yaml
spring.cloud.gateway.routes.security.public-routes.enabled: false
```
