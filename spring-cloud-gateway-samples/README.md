# spring-cloud-gateway-samples

## Getting start

### auth-server
Launch auth-server: this will start an esay config of oauth2 authorization server. 
To log-in, use these two user:password : `user:user` with role 'USER'  or `admin:admin` with role 'ADMIN'

### eureka
Launch Eureka service discovery to test SCGateway Openapi discovery with service-a.

### service-a
Launch service-a: this will start a simple app with a controller.

### gateway
Launch gateway. 

#### spring-cloud-gateway-filters
In the application.yml some routes are configured to test the filters provided by the plugin

| Url | Description |
| --- | --- |
| http://localhost:8181/test-authorization/sample | the validation of a basic authentification |
| http://localhost:8181/test-authorization-token/sample | the validation of jwt with 'user:user' is successful |
| http://localhost:8181/test-authorization-token-ko/sample | the validation of jwt with 'user:user' is failed |


#### spring-cloud-gateway-openapi
<i>For the demo, you need to run the gateway and service-a with the profile "eureka".</i>
<br>
To test, go to http://localhost:8181/swagger-ui.html and you should have access to swagger interface with SERVICE-A api's:
<p align="center">
  <img src="doc/spring-cloud-gateway-openapi.png" alt="spring-cloud-gateway-openapi" width="50%"/>
</p>


