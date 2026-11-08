# spring-cloud-gateway-commons

The contracts the gateway plugins share. It exists to answer one question: **when the console in
front of a gateway is behind a login, what happens to the HTTP endpoints the other plugins
serve?** A plugin knows the paths it answers; only the console knows how to authenticate a
visitor. Neither can depend on the other, so this module is the thin thing in the middle.

It carries no configuration property of its own: the credentials a plugin accepts are bound
under that plugin's prefix.

## Install

Every plugin that declares paths already depends on it — add it explicitly only when writing
one of your own. It brings a small jar, `spring-core` and `slf4j-api`, all already on the
classpath of any gateway.

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-commons</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Declaring the paths a plugin serves

```java
@Bean
SecuredPaths routeApiSecuredPaths() {
    return SecuredPaths.api("/api/gateway/routes", "/api/gateway/routes/{id}");
}
```

Four kinds, because "who may reach this path" is not the same question everywhere:

| Factory | Meaning | Example |
| --- | --- | --- |
| `SecuredPaths.governed(...)` | Follows the console: open while it is open, behind its login once it is not | The Swagger UI, a dashboard |
| `SecuredPaths.open(...)` | Reachable without a principal whatever the console does | An endpoint the sibling instances poll with no credentials |
| `SecuredPaths.write(...)` | Always asks for a principal, because reaching it changes the gateway | The route management page |
| `SecuredPaths.api(...)` | The same, and left out of the CSRF protection | The route management API, called with a token |

A path is matched exactly, or with a pattern that stays inside the namespace the plugin owns. A
blanket `/**` would also open the gateway routes an application declared under the same prefix
— paths the plugin does not serve and did not intend to expose.

`SecuredPathsContribution` is the interface behind the record, so a module that already has a
declaration type of its own (the console and its `UiSecuredPaths`) implements it rather than
growing a second one.

**Who reads it:** the [console](../spring-cloud-gateway-ui/README.md), through the security
chain it contributes — see
[The endpoints of the other plugins](../spring-cloud-gateway-ui/README.md#the-endpoints-of-the-other-plugins).
Its chain is ordered ahead of the ones the plugins declare for themselves. Nothing else: a
gateway assembled without the console has nobody collecting the contributions, and each plugin
keeps whatever chain it declares on its own. **Declaring paths is never what closes them.**

The paths declared as read or open are also what the console keeps out of the audit trail:
browsing a dashboard is not gateway traffic. The ones that change the gateway are not excluded —
who created a route, and when, is the very thing an audit trail is kept for.

## Securing the paths without the console

A gateway assembled without the console still has to close what changes it or hands out its
weaknesses. `PluginSecurityProperties` is the block each plugin binds under its own prefix
for that — `...mcp.security`, `...routes-database.security`, `...pentest.passive.security` —
spelled as the console spells its own:

```yaml
<prefix>.security:
  user:                        # a local user over HTTP Basic, held by that chain alone
    name: admin
    password:                  # no password, no local user
    roles: [ADMIN]
  oauth2.resourceserver.jwt:
    issuer-uri:                # the issuer of the Bearer tokens; unset, the application's decoder
    audiences: []              # what their aud must carry one of
```

A chain built over it is one condition and two calls, so every plugin answers the same
credentials the same way:

```java
@Bean
@ConditionalOnBean(SecurityWebFilterChain.class)
@ConditionalOnPluginAuthentication(prefix = "spring.cloud.gateway.server.webflux.my-plugin.security")
SecurityWebFilterChain myPluginSecurityWebFilterChain(ServerHttpSecurity http, MyPluginProperties properties,
        ObjectProvider<ReactiveUserDetailsService> userDetailsService,
        ObjectProvider<ReactiveAuthenticationManager> authenticationManager, BeanFactory beanFactory) {
    http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(MY_PATHS));
    http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
    PluginSecurityChain.basic(http, properties.getSecurity().getUser(), userDetailsService, authenticationManager);
    PluginSecurityChain.bearer(http, properties.getSecurity().getOauth2().getResourceserver().getJwt(), beanFactory);
    return http.build();
}
```

`@ConditionalOnPluginAuthentication` matches when there is something to authenticate
against: the local user or the issuer under the prefix, else a `ReactiveUserDetailsService`,
a `ReactiveAuthenticationManager` or a `ReactiveJwtDecoder` of the application. A blank value
is no value. Without it a chain would ask for a principal nobody can present.
`@ConditionalOnBean(SecurityWebFilterChain.class)` is the other half: with no chain at all,
Spring Security's own "everything authenticated" chain already covers the paths, and a chain
matching the plugin alone would displace it.

`PluginSecurityChain.basic` configures HTTP Basic over the local user, else over the user
directory or the authentication manager of the application, and turns off CSRF, the form
login and the logout. `PluginSecurityChain.bearer` configures the resource server over the
issuer, else over the decoder the application declared, and says whether it did. It takes
the bean factory rather than the decoder so that the plugin names no JWT class: a plugin
declares `spring-security-oauth2-jose` and `spring-security-oauth2-resource-server` as
optional, and a gateway without them simply accepts no Bearer token on that chain. A clear-text password is encoded at start-up; one already carrying the
id of its encoder, such as `{bcrypt}...`, is taken as is. The issuer is asked for its keys on
the first token that arrives, never at start-up, and `AudienceCheckingJwtDecoder` enforces
the audiences over it. `LocalUserAuthentication` and `IssuerJwtDecoders` are the two helpers
underneath, for a module with a properties block of its own such as the console. Everything
here but the properties class needs Spring Security on the classpath.

## Reading the route sources

`RouteDefinitionSources` is how the console, the passive analyser and the MCP server read the
route definition sources of the gateway, so a source is listed and named alike everywhere:

```java
for (RouteDefinitionLocator source : RouteDefinitionSources.sources(locators)) {
    String name = RouteDefinitionSources.name(source);            // "Properties", "Files", "Database", "Custom"
    Mono<List<RouteDefinition>> read = RouteDefinitionSources.read(source, Duration.ofSeconds(5));
}
```

`sources` leaves out the composite locator Spring Cloud Gateway builds over the others, which
would repeat every route; `read` bounds the whole read of a source and propagates its error,
so each caller decides whether a source that cannot be read is skipped or reported.

## Naming the running instance

`InstanceIdentity` resolves, once at startup, the name a locally collected figure is reported
under: the configured id, then the `HOSTNAME` environment variable — which Kubernetes sets to
the pod name — then the host name itself. The lookup can hit DNS, which is why it is not done
per request.

It lives here because more than one plugin needs it: the
[metrics plugin](../spring-cloud-gateway-metrics/README.md) labels its figures with it, the
[service graph plugin](../spring-cloud-gateway-service-graph/README.md) labels its graph and
keys its Redis entry with it, and neither should depend on the other to say which pod answered.
