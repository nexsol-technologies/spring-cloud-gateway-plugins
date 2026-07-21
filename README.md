<p align="center">
  <img src="logo.png" alt="spring-cloud-gateway-plugins-logo" width="50%"/>
  <br>
  <em>Plugins for Spring Cloud Gateway.</em>
  <br>
</p>
<hr>

# Spring Cloud Gateway plugins by [neXsol technologies](https://nexsol.tech)

## Getting Started

[spring-cloud-gateway-audit](spring-cloud-gateway-audit/README.md)<br>
This plugin audits gateway requests and responses (JWT, request, response and trace attributes, toggled by logical group) and pushes them to a pluggable provider (Redis, Kafka, RabbitMQ, database, ...). It works per-route (the Audit gateway filter) or globally (a web filter).

[spring-cloud-gateway-routes](spring-cloud-gateway-routes/README.md) <br>
This plugin manages gateway route definitions from pluggable sources aggregated into a route locator: a database (with a simple GUI), JSON/YAML files (GitOps friendly), and OpenAPI contracts.

[spring-cloud-gateway-filters](spring-cloud-gateway-filters/README.md)<br>
This plugin provides various custom filters for Spring Cloud Gateway, including Authorization, ConvertHttpMethod, and Recaptcha.

[spring-cloud-gateway-oauth2](spring-cloud-gateway-oauth2/README.md)<br>
This plugin adds OAuth2 support to Spring Cloud Gateway, making it easier to implement multi-tenant OAuth2 authentication and JWT validation. It includes the AuthorizationToken filter to validate an access token.

[spring-cloud-gateway-hub-openapi](spring-cloud-gateway-hub-openapi/README.md)<br>
This plugin aggregates the OpenAPI documentation of downstream services into a hub. (To generate routes from an OpenAPI contract instead, see [spring-cloud-gateway-routes-openapi](spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md).)

[spring-cloud-gateway-ui](spring-cloud-gateway-ui/README.md)<br>
This plugin provides a Spring Boot Admin-like web UI shell served under `/ui`: a home page with a collapsible side menu, where each gateway plugin lights up its own menu entry automatically when present on the classpath.


## samples

[spring-cloud-gateway-samples](spring-cloud-gateway-samples/README.md)
This project provides some examples.

## Spring Cloud Gateway documentation

[reference documentation](https://docs.spring.io/spring-cloud-gateway/reference/)



## Getting Help

Having trouble with Spring cloud Gateway plugins by Nexsol? We’d like to help!

 * Report bugs at https://github.com/nexsol-technologies/spring-cloud-gateway-plugins/issues
 
## Trademarks and licenses
The source code of nexsol's Spring cloud Gateway is licensed under [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)

Spring, Spring Boot and Spring Cloud are trademarks of [Broadcom Inc.](https://www.broadcom.com/) and/or its subsidiaries in the U.S. and other countries.
