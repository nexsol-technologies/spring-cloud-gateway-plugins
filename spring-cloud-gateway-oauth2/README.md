# spring-cloud-gateway-oauth2

This plugin provides oauth2 support for Spring Cloud Gateway

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

### Default converter
By default, this plugin provides a default Converter to parse Jwt to extract Spring Security GrantedAuthority.

Search in access token these claims to create GrantedAuthority :

| claims                      | Description | 
| :-------------------------- |:--------------| 
| `$.realm_access.roles`        | In Keycloak, the roles list contains the global roles of the realm  |
| `$.resource_access.xxx.roles` | In Keycloak, Contains client-specific roles for xxx. xxx is the current application name `spring.cloud.gateway.resourcename` if defined or `spring.application.name` by default | 
| `$.permissions`              |  | 
| `$.roles`                    |  | 


### Configurable converter
You can't declare your own paths to find claims in Jwt to extract Spring Security GrantedAuthority.
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        granted-authorites-mapping:
          json-path:
          - '$.my-custom-roles'
```



## Multitenancy

This plugin simplifies the implementation of multi-tenant OAuth2 authentication and JWT validation in Spring Cloud Gateway. It allows your gateway to dynamically configure and validate requests based on tenant-specific OAuth2 settings, enabling seamless support for multiple tenants with their own identity providers.

<b>Tenant-Specific OAuth2 Configurations</b>: Configure OIDC issuer uri for each tenant.<br> The Issuer URI is a discovery endpoint that returns OpenID Connect or OAuth 2.0 metadata related to your authorization server.
<br>
```
spring:
  security:
    oauth2:
      resourceserver:
        multitenant:
          - id: keycloak
            issuer-uri: https://keycloakhost:keycloakport/realms/{realm}
          - id: okta
            issuer-uri: https://{yourOktaOrg}
```
<br><br>

<b>Dynamic Tenant Identification</b> (Not Implemented): Extract tenant information from request headers, subdomains, or other sources. 

