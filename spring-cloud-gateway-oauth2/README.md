# spring-cloud-gateway-oauth2

This project provides oauth2 support for Spring Cloud Gateway

```xml
    <properties>
        <spring-cloud-gateway-plugins.version>0.0.1-SNAPSHOT</spring-cloud-gateway-plugins.version>
    </properties>
    
    <dependencies>
        <dependency>
           <groupId>ch.nexsol.gateway</groupId>
           <artifactId>spring-cloud-gateway-oauth2</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```
## Converter for GrantedAuthority

Provide a custom Converter to pasre Jwt to extract Spring Security GrantedAuthority.

Search in access token these claims to create GrantedAuthority :

| claims                      | Description | 
| :-------------------------- |:--------------| 
| `$.realm_access.roles`        | Keycloak  |
| `$.resource_access.xxx.roles` | Keycloak, xxx is the current application name `spring.cloud.gateway.resourcename` if defined or `spring.application.name` by default | 
| `$.permissions`              |  | 
| `$.roles`                    |  | 



## Multitenancy

This library simplifies the implementation of multi-tenant OAuth2 authentication and JWT validation in Spring Cloud Gateway. It allows your gateway to dynamically configure and validate requests based on tenant-specific OAuth2 settings, enabling seamless support for multiple tenants with their own identity providers.


<b>Tenant-Specific OAuth2 Configurations</b>: Configure OIDC issuer uri for each tenant.<br> The Issuer URI is a discovery endpoint that returns OpenID Connect or OAuth 2.0 metadata related to your authorization server.

<br>

```
spring:
  security:
    oauth2:
      resourceserver:
        multitenant:
          - https://keycloakhost:keycloakport/realms/{realm}
          - https://{yourOktaOrg}
```

<b>Dynamic Tenant Identification</b>: Extract tenant information from request headers, subdomains, or other sources. (Not Implemented yet)

