# spring-cloud-gateway-commons

The contracts the gateway plugins share. One so far, and it exists to answer a single
question: **when the console in front of a gateway is behind a login, what happens to the
HTTP endpoints the other plugins serve?**

A plugin knows the paths it answers. Only the console knows how to authenticate a visitor
— its login page, its identity providers, its Bearer tokens, its roles. Neither can depend
on the other: the hub is on the classpath of consoles that do not exist yet, and the console
is assembled without knowing which plugins came with it. This module is the thin thing in
the middle.

It carries no dependency of its own. Adding it to a plugin adds a jar of two types.

## Declaring the paths a plugin serves

```java
@Bean
SecuredPaths routeApiSecuredPaths() {
    return SecuredPaths.api("/api/gateway/routes", "/api/gateway/routes/{id}");
}
```

Four kinds, because "who may reach this path" is not the same question everywhere:

| Factory | Meaning | Example |
|---|---|---|
| `SecuredPaths.governed(...)` | Follows the console: open while it is open, behind its login once it is not | The Swagger UI, a dashboard |
| `SecuredPaths.open(...)` | Reachable without a principal whatever the console does | An endpoint the sibling instances poll with no credentials |
| `SecuredPaths.write(...)` | Always asks for a principal, because reaching it changes the gateway | The route management page |
| `SecuredPaths.api(...)` | The same, and left out of the CSRF protection | The route management API, called with a token |

A path is matched exactly, or with a pattern that stays inside the namespace the plugin
owns. A blanket `/**` would also open the gateway routes an application declared under the
same prefix — paths the plugin does not serve and did not intend to expose.

`SecuredPathsContribution` is the interface behind the record, so a module that already has
a declaration type of its own (the console and its `UiSecuredPaths`) implements it rather
than growing a second one.

## Who reads it

The [UI console](../spring-cloud-gateway-ui/README.md), through the security chain it
contributes — see
[The endpoints of the other plugins](../spring-cloud-gateway-ui/README.md#the-endpoints-of-the-other-plugins).
Its chain is ordered ahead of the ones the plugins declare for themselves, so it answers
first for the paths it takes over.

Nothing else. A gateway assembled without the console has nobody collecting the
contributions: the beans sit there unread, and each plugin keeps whatever chain it declares
on its own. Declaring paths is never what closes them.

## Auditing

The paths declared as read or open are also what the console keeps out of the audit trail:
browsing a dashboard is not gateway traffic. The ones that change the gateway are not
excluded — who created a route, and when, is the very thing an audit trail is kept for.
