# spring-cloud-gateway-commons

The contracts the gateway plugins share. It exists to answer one question: **when the console in
front of a gateway is behind a login, what happens to the HTTP endpoints the other plugins
serve?** A plugin knows the paths it answers; only the console knows how to authenticate a
visitor. Neither can depend on the other, so this module is the thin thing in the middle.

It carries no configuration property of its own.

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

## Naming the running instance

`InstanceIdentity` resolves, once at startup, the name a locally collected figure is reported
under: the configured id, then the `HOSTNAME` environment variable — which Kubernetes sets to
the pod name — then the host name itself. The lookup can hit DNS, which is why it is not done
per request.

It lives here because more than one plugin needs it: the
[metrics plugin](../spring-cloud-gateway-metrics/README.md) labels its figures with it, the
[service graph plugin](../spring-cloud-gateway-service-graph/README.md) labels its graph and
keys its Redis entry with it, and neither should depend on the other to say which pod answered.
