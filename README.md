<p align="center">
  <img src="logo.png" alt="spring-cloud-gateway-plugins-logo" width="50%"/>
  <br>
  <em>Plugins for Spring Cloud Gateway.</em>
  <br>
</p>
<hr>

# Spring Cloud Gateway plugins by [neXsol technologies](https://nexsol.tech)

## Plugins

[spring-cloud-gateway-audit](spring-cloud-gateway-audit/README.md)<br>
Audits gateway requests and responses (JWT, request, response and trace attributes, toggled by logical group) and pushes them to a pluggable provider (Redis, Kafka, database, ...). Auditing runs per route (the `Audit` gateway filter) or globally (a web filter).

[spring-cloud-gateway-metrics](spring-cloud-gateway-metrics/README.md)<br>
Collects the per-route request figures the traffic view plots, from a pluggable source. The default reads the meter registry of the running instance; behind a load balancer that is only its own share of the traffic, so a provider consolidates every instance instead (Prometheus, Redis, or the service registry). Every figure is reported with the coverage it was computed over.

[spring-cloud-gateway-routes](spring-cloud-gateway-routes/README.md)<br>
Manages gateway route definitions from pluggable sources aggregated into a route locator: a database (with a management GUI), JSON/YAML files (GitOps friendly), a Config Server and OpenAPI contracts.

[spring-cloud-gateway-filters](spring-cloud-gateway-filters/README.md)<br>
Custom gateway filters: `Authorization`, `ConvertHttpMethod`, `CorrelationId` and `Recaptcha`.

[spring-cloud-gateway-oauth2](spring-cloud-gateway-oauth2/README.md)<br>
OAuth2 support for Spring Cloud Gateway: multi-tenant authentication, JWT validation, and the `AuthorizationToken` filter validating an access token per route.

[spring-cloud-gateway-hub-openapi](spring-cloud-gateway-hub-openapi/README.md)<br>
Aggregates the OpenAPI documentation of downstream services into a hub. To generate routes from an OpenAPI contract instead, see [spring-cloud-gateway-routes-openapi](spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md).

[spring-cloud-gateway-ui](spring-cloud-gateway-ui/README.md)<br>
A Spring Boot Admin-like web UI served under `/ui`, where each gateway plugin lights up its own view automatically when present on the classpath: an overview home page, the resolved routes with the source each one came from, a route tester answering which route would handle a described request (and why, predicate by predicate), a traffic chart and a live tail of the audit events.

## Samples

[spring-cloud-gateway-samples](spring-cloud-gateway-samples/README.md) — one runnable gateway per plugin, plus a few exercising them in combination.

## Spring Cloud Gateway documentation

[Reference documentation](https://docs.spring.io/spring-cloud-gateway/reference/)

## Getting help

Report bugs and ask questions at
https://github.com/nexsol-technologies/spring-cloud-gateway-plugins/issues

## Trademarks and licenses

The source code of neXsol's Spring Cloud Gateway plugins is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Spring, Spring Boot and Spring Cloud are trademarks of [Broadcom Inc.](https://www.broadcom.com/) and/or its subsidiaries in the U.S. and other countries.
