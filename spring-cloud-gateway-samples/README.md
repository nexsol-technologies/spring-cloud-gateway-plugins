# spring-cloud-gateway-samples

## Getting start

### auth-server
launch auth-server: this will start an esay config of oauth2 authorization server.
to log-in, use these two user:password : `user:user` with role 'USER'  or `admin:admin` with role 'ADMIN'

### service-a
launch service-a: this will start a simple app with a controller.

### gateway
launch gateway. in its application.yml some route are configured to test the filter provided in the spring-cloud-gateway-filters

| http://localhost:8181/test-authorization/sample | the validation of a basic authentification |
| http://localhost:8181/test-authorization-token/sample | the validation of jwt with 'user:user' is successful |
| http://localhost:8181/test-authorization-token-ko/sample | the validation of jwt with 'user:user' is failed |
