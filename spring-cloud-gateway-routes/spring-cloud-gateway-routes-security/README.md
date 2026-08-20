# spring-cloud-gateway-routes-security

Lets a gateway route declare itself **public**, so requests targeting it bypass Spring
Security (Basic auth, OAuth 2.0). Works with every route source: a route is public as soon as
its metadata carries a truthy `public` entry.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-routes-security</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

Active as soon as the module is on the classpath.

| Property | Default | What it does |
| --- | --- | --- |
| `...routes-security.public-routes.enabled` | `true` | Registers the permissive chain; `false` unwires the feature |

Full key: `spring.cloud.gateway.server.webflux.routes-security.public-routes.enabled`.

## Declaring a public route

Set `public: true` in the route metadata, whatever the source.

**Files, Config Server:**

```yaml
routes:
  - id: public-api
    uri: https://backend.example.org
    predicates:
      - Path=/public/**
    metadata:
      public: true
```

**OpenAPI source:**

```yaml
spring.cloud.gateway.server.webflux.routes-openapi.sources[0].metadata.public: true
```

**Database:** tick **Public route** in the routes view, or set the `public_route` column
(`publicRoute` in the REST API).

## How it works

Spring Security runs **before** the gateway resolves the matching route, so at security-filter
time the target route is not known yet. `PublicRouteMatcher` replays the route predicates
against the exchange — exactly as the gateway does later — to resolve the target route up
front, and matches when that route is flagged public.

`RoutesSecurityAutoConfiguration` then registers a high-priority `SecurityWebFilterChain`,
ordered ahead of the application chains, whose security matcher is that matcher. Since Spring
Security serves a request with the **first** chain whose matcher matches, a public route is
handled by this permissive chain and never reaches the Basic-auth or OAuth 2.0 chains. Every
other request falls through unchanged.

## Sample

[gateway-secured](../../spring-cloud-gateway-samples/gateway/gateway-secured/README.md) — port
`8211`.
