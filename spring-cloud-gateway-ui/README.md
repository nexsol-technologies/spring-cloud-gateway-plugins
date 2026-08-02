# spring-cloud-gateway-ui

A Spring Boot Admin-like web UI shell for Spring Cloud Gateway: a home page served under
`/ui` with a collapsible side menu. Each gateway plugin lights up its own menu entry
automatically when it is present on the classpath.

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol-tech.gateway</groupId>
           <artifactId>spring-cloud-gateway-ui</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

The plugin auto-configures itself; no extra setup is required. Start the gateway and open
`http://<host>:<port>/ui`.

## The shell

* **Collapsible side menu** &mdash; the toggle button in the top-left corner switches the
  menu between the expanded state (icon + label) and the collapsed state (icon only). The
  choice is remembered across page loads.
* **Thin scrollbar** &mdash; the menu scrolls independently with a slim scrollbar.
* **Home page** &mdash; the overview of the gateway, rendered inside the shell.
* **Remembered controls** &mdash; the switches and drop-downs of a view are restored as they
  were last left. Search boxes are not: a forgotten query hiding every row reads as an empty
  view.

Built with Thymeleaf, Bootstrap, HTMX and plain CSS/JS, served as static resources (no
CDN, offline friendly).

Each view activates on its own, from what is on the classpath, so the UI only ever shows
what the application actually runs:

| View | Path | Activates when |
| --- | --- | --- |
| Home | `/ui` | always |
| Routes | `/ui/routes` | the gateway route definition types are present |
| Database routes | `/ui/routes/db` | `spring-cloud-gateway-routes-database` is present |
| Route tester | `/ui/routes/test` | the gateway route table type is present |
| Traffic | `/ui/metrics` | Micrometer is present |
| OpenAPI | `/ui/openapi` | `spring-cloud-gateway-hub-openapi` is present and `spring.cloud.gateway.server.webflux.hub-openapi.enabled` is `true` |
| Audit | `/ui/audit` | `spring-cloud-gateway-audit-core` is present and `spring.cloud.gateway.server.webflux.audit.enabled` is not `false` |

## Home page

An overview of the gateway rather than a welcome text: the uptime, one tile per figure
contributed by the active views (routes and their sources, calls, average latency, client
errors, server errors, audited exchanges) and a link to every view that lit up.

Client and server errors get a tile each, for the same reason the traffic view separates
them: a wave of 404 and a backend outage call for different actions.

The figures come from the views themselves: each one contributes an `OverviewContribution`
bean declared next to it and guarded by the same condition, so the home page never
references the optional types those views are built on. Any module can add a tile the same
way:

```java
@Bean
OverviewContribution quotaOverviewContribution(QuotaService quotaService) {
    // label, value (already formatted), one-line detail, sort weight
    return () -> Flux.just(new OverviewStat("Quota", quotaService.used() + "%", "of the monthly budget", 60));
}
```

A contribution that fails is dropped rather than failing the page.

## Routes view

Every route definition the gateway resolves, **attributed to the source it was read from**
&mdash; properties, database, YAML/JSON files, OpenAPI contracts, Config Server or any
third-party locator. This is what answers "which configuration actually won" when several
sources declare routes at once.

Each source is queried individually instead of through the gateway's aggregate, and the
source name is derived from the locator class name, so a locator contributed by any plugin
shows up correctly without this module knowing about it.

The inventory is **read once, then served while it is refreshed**. Only the very first
reader waits on the sources; a `RefreshRoutesEvent` marks the snapshot stale instead of
dropping it, and the read it triggers runs in the background. Navigating between this page
and the home page therefore queries nothing, and a locator that reaches the network &mdash;
service discovery, a remote contract &mdash; is never called in the middle of a page render.
This matters behind a discovery client: a refresh event arrives on every heartbeat, so a
snapshot dropped on each event would leave every view paying for a full read of a registry
holding hundreds of services. The page then shows the inventory as of the previous read; the
*Refresh view* action is what waits for a current one.

A refresh already in flight is shared rather than started again, so several views opened at
once cost one read. Each source is given five seconds to answer; past that it is dropped
from the snapshot with a warning, as a source that fails to be read is, so one unreachable
source cannot hold the page.

**Columns** &mdash; route (its id, with the target it resolves to under it), source, order,
predicates and filters. A predicate or filter is rendered the way it was declared, not as a
raw argument map: positional arguments read back as the YAML shortcut (`Path=/api/**`,
`StripPrefix=1`), named ones as a call (`Path(patterns=/api/**, matchTrailingSlash=true)`).

The table scrolls horizontally rather than squeezing: a rewrite filter carries a whole
regexp, and a target URI is shown on one line, cut with an ellipsis, with the full value in
its tooltip.

**Duplicate ids** &mdash; a route id declared by more than one source is badged. Both
definitions do reach the route table; the lowest order is matched first.

**Filter** &mdash; the search box narrows the table on route id, target or source.

**The two actions have different targets**, which is why they are named after what they
refresh:

| | Refresh view | Rebuild gateway routes |
| --- | --- | --- |
| Does | re-reads the sources, re-renders this table | publishes a `RefreshRoutesEvent`, then re-renders |
| Affects | this page only | the gateway route table used to route traffic |

*Refresh view* drops the cached inventory and calls every locator again. What that picks up
depends on the locator: a database or discovery source is queried live, while a file or
Config Server source serves the snapshot it last loaded &mdash; those reload through their
own plugin (a file watch, a poll, `/actuator/refresh`), never through this page.

*Rebuild gateway routes* aims at the gateway: `CachingRouteLocator` drops its cached `Route`
objects and rebuilds them from the current definitions, exactly as the gateway actuator
refresh endpoint does. This is what makes a definition change take effect on traffic &mdash;
though the routes-database plugin already publishes that event itself when a route is created
or deleted, so it is mostly for definitions changed behind the API's back (a row inserted
straight into the database, for instance).

## Route tester

Describe a request &mdash; method, path with an optional query string, headers, one
`Name: value` pair per line &mdash; and see which route would handle it. **Nothing is sent
downstream**: the request is never dispatched, only matched.

The verdict comes from the route table itself: the routes are read from the `RouteLocator`
and each one is evaluated with the very predicates the gateway would apply, in the very
order it applies them. The first match wins, as it does at runtime.

Each candidate is then broken down **predicate by predicate**, which is what turns a bare
"no match" into an answer:

```
✗ no match   alpha → http://alpha.example.com        Properties   order 0
   ✓ Paths: [/alpha/**], match trailing slash: true
   ✗ Methods: [POST]
```

The path matched, the method did not. The filters the winning route would apply are listed
under it.

A `Host` header, when given, is used as the request host, so host-based and multi-tenant
routing can be tested. The tested request carries no body, and a predicate reading something
only an in-flight call has (a response, a session) is reported as failed against that
predicate rather than failing the whole test.

## Audit view

The tail of the exchanges the audit plugin captured, newest first: time, method, path,
status (colour-coded by class), user, ip and trace id. A row expands into **every** attribute
the audit plugin collected for that exchange &mdash; JWT claims, headers, trace and span ids.

Filter by status class (2xx to 5xx) and search across method, path, user, ip and trace id.
The **Live** switch polls every 3 seconds.

The events are read on their way to the audit backend: the plugin's `AuditEventPublisher`
bean is wrapped in a decorator that keeps a copy, so the view works whichever backend is
configured &mdash; the default publisher, Redis, Kafka, a database or an
application-provided one.

The tail is a bounded in-memory buffer of at most 500 events, cleared on restart: it shows
the gateway's own recent traffic without querying the backend, which keeps the durable copy.
Auditing must be enabled on a route (the `Audit` gateway filter) or globally (the audit web
filter) for anything to show up.

The console keeps itself out of the trail. Its own paths &mdash; the pages, the HTMX
fragments they poll (`/ui/audit/events`, `/ui/metrics/data`) and the static assets
(`/js/echarts.min.js` and the rest) &mdash; are added to
`spring.cloud.gateway.server.webflux.audit.web-filter.exclude-paths`, so the global audit
web filter never records them: the view shows the traffic the gateway routed, not the
traffic of looking at it. The exclusions are the exact paths the active views declare
through `UiSecuredPaths`, never a `/ui/**` pattern, so a gateway route declared under `/ui`
keeps being audited. Add your own with the same property.

Setting `spring.cloud.gateway.server.webflux.audit.enabled=false` turns the audit plugin off,
and with it this view: the menu entry, the home page figure and the `/ui/audit` paths all
disappear, exactly as if the plugin were not on the classpath.

## Traffic view

When Micrometer is on the classpath, a **Traffic** entry appears (`/ui/metrics`), built
from the gateway request metrics (`spring.cloud.gateway.requests`): calls, average and max
latency, errors and error rate per route. The page reads top to bottom &mdash; summary,
then map, then the exact numbers.

**Summary** &mdash; routes called, total calls, weighted average latency, total 4xx, total 5xx.

**4xx and 5xx are counted apart.** A 4xx is the caller being turned away (unknown path,
missing rights, malformed request); a 5xx is the gateway or the backend failing. Summing
them would make a scanner hitting unknown paths look like an outage, so each has its own
tile, its own column and its own axis. The bubble colour and the *Error rate* badge stay on
the 5xx: they answer "is it broken", not "is it being refused".

The **4xx** switch removes the client errors from the view entirely &mdash; tile, column,
axis options and the question built on them &mdash; for reading the traffic as pure
server-side health. A metric selection pointing at a hidden column falls back rather than
plotting what was just removed.

**Map** &mdash; one bubble per route (ECharts, vendored locally). Rather than exposing raw
axes, the chart answers a named question that also picks the metrics:

| Question | Reads as |
| --- | --- |
| Where should I optimise? | calls &times; avg latency &mdash; top-right is busier *and* slower than the median route |
| Where does it break? | calls &times; 5xx rate &mdash; top-right fails on traffic that matters |
| Who gets rejected? | calls &times; 4xx rate &mdash; top-right is refused on traffic that matters (wrong path, missing permission) |
| Which routes spike? | avg &times; max latency &mdash; outliers have a worst case far above their average |
| Custom&hellip; | re-opens the raw X / Y / bubble-size pickers |

Dashed lines mark the median of both axes and the four quadrants are labelled in place, so
a bubble's position is readable without a legend. Bubble colour is the error rate
(green &rarr; red), and each bubble is labelled with its route id. The **3D** switch adds a
third metric on a rotatable Z axis (echarts-gl); **Auto** polls every 5 seconds.

Each question also carries its Z axis, applied when the question is picked: *Who gets
rejected?* plots the client errors, the others the server errors. It is only a starting
point &mdash; changing Z afterwards sticks until another question is selected.

**All routes** &mdash; the same data as a sortable table (click any column) for the exact
figures, with the error rate as a colour-coded badge.

The view is fed by `GET /ui/metrics/data` (JSON). It stays hidden when no meter registry is
available, and shows an empty state until traffic has flowed through the gateway (gateway
request metrics are enabled by default).

### Excluding routes

Some routes carry no traffic worth reading &mdash; the documentation routes the OpenAPI hub
publishes are contracts being fetched, not usage. They are left out by default:

```yaml
spring.cloud.gateway.server.webflux.metrics:
  excluded-routes:
    - openapi-docs-.*            # the default
```

Each entry is a regular expression matched against the **route id**, and the whole id must
match (`docs` excludes `docs`, not `docs-public`). Setting the property replaces the default
list, so keep `openapi-docs-.*` if you want to keep hiding them:

```yaml
spring.cloud.gateway.server.webflux.metrics:
  excluded-routes:
    - openapi-docs-.*
    - internal_.*
    - .*_healthcheck
```

An empty list shows every route. A malformed expression is dropped with a warning rather
than failing the application &mdash; losing a filter beats a gateway that does not start.

The exclusion applies to the whole view: the summary, the map, the table and the traffic
figures on the home page all read the same filtered set.

## Menu entries (Spring Boot Admin style)

Menu entries come from a registry: any `NavItem` bean present in the application context
is collected by `GatewayUiMenu` and rendered in the sidebar.

The built-in `home` entry is always present. A `routes` entry is contributed by this
plugin but activates &mdash; only when the routes-database API is on the classpath &mdash;
through a conditionally-guarded bean:

```java
@Bean
@ConditionalOnClass(name = "ch.nexsol.gateway.database.controller.RouteViewController")
NavItem routesNavItem() {
    // id, label, icon (SVG symbol id from the shell sprite), href, order
    return new NavItem("routes", "Database routes", "icon-plugin", "/ui/routes/db", 10);
}
```

Any module can light up its own entry the same way, simply by declaring a `NavItem` bean
(optionally guarded by a condition). Icons reference the SVG sprite declared in
`templates/dashboard/fragments/layout.html` (`icon-home`, `icon-plugin`, `icon-route`,
`icon-target`, `icon-chart`, `icon-book`, `icon-list`).

The built-in entries are ordered `home` (0), `Routes` (5), `Database routes` (10),
`Route tester` (15), `Traffic` (20), `OpenAPI` (25) and `Audit` (30), leaving room for your
own in between.

## Hosting a plugin page inside the shell

A plugin renders its own page inside the shell by targeting the layout fragment and
supplying a content and a scripts slot:

```html
<html th:replace="~{dashboard/fragments/layout :: layout('Title', ~{:: #content}, ~{:: #scripts})}">
<body>
    <div id="content"> ... page markup ... </div>
    <script id="scripts"> ... page JS (Bootstrap/HTMX already loaded) ... </script>
</body>
</html>
```

The sidebar is populated automatically for every rendered view by `GatewayUiModelAttributes`
(a `@ControllerAdvice` exposing `navItems`); the controller only sets `activeNav` to its
own entry id. The database routes management page (`spring-cloud-gateway-routes-database`)
is wired exactly this way and shows up under `/ui/routes/db`.

## OpenAPI view

When [spring-cloud-gateway-hub-openapi](../spring-cloud-gateway-hub-openapi/README.md) is on
the classpath **and** enabled, the shell lights up an `OpenAPI` entry serving the contracts
the hub aggregates, rendered with [Scalar](https://github.com/scalar/scalar) at `/ui/openapi`.

The page reads the list of contracts from the SpringDoc `swagger-config` endpoint, which the
hub keeps in sync with the discovered services, and feeds them to Scalar as its document
sources: one entry per service in the selector. Since the hub rewrites each contract's
`servers` section to the gateway, Scalar's request client calls the gateway, not the service
directly. When nothing has been aggregated, the contract of the gateway itself is shown.

A custom `springdoc.api-docs.path` is honoured &mdash; the view is handed the configured
paths, it does not assume `/v3/api-docs`.

**Vendor extensions** &mdash; Scalar renders the extensions it knows about (`x-internal`,
`x-displayName`, `x-badges`, `x-codeSamples`, `x-tagGroups`, the `x-enum*` family, `x-example`,
`x-scalar-*`) and drops the others, so what a service documents of its own &mdash; the roles a
resource requires, the version it appeared in &mdash; is never displayed. A Scalar plugin
takes those over. The contracts are left untouched: Scalar fetches only the one on
screen and parses it itself, YAML included.

Each extension is declared with the label it reads under, the plugin registry matching an
extension by its exact name:

```yaml
spring.cloud.gateway.server.webflux.ui.openapi:
  extensions:
    x-roles: Required roles
    x-from-application-version: Since
```

An operation carrying `x-roles` then reads `Required roles — admin, auditor`, a list shown
comma-separated and an object as JSON. An extension left out of the mapping is not shown, and
adding one takes a restart, not a rebuild.

Scalar renders extensions on the document, on `info`, on a tag, on a schema and on an
operation. A path item is not one of its rendering points, so an extension declared there
does not reach the operations under it. `x-badges` is rendered natively, as a badge next to
the operation, which suits a short label better than this mapping does.

The Scalar bundle ships with the plugin (`/js/scalar.standalone.js`, `@scalar/api-reference`
1.63.0, 3.6 MB) and its default web fonts are switched off, so the view works on an isolated
network without reaching any CDN.

## Spring Security

When Spring Security is on the classpath, the plugin contributes its own
`SecurityWebFilterChain` so the shell keeps working behind the authentication of the
application. Nothing has to be declared.

The chain permits **exactly** the paths the active views serve &mdash; never a `/ui/**`
pattern. A gateway route declared under `/ui` (say `/ui/find_pwd`) must not inherit the UI
permissions, and a view that is not active leaves its path closed. Each view declares its
own endpoints and assets through a `UiSecuredPaths` bean:

```java
@Bean
UiSecuredPaths auditTailSecuredPaths() {
    return new UiSecuredPaths("/ui/audit", "/ui/audit/events", "/js/gateway-audit.js");
}
```

A plugin hosting its own page inside the shell declares its paths the same way, and they
join the chain.

What is permitted out of the box: `/ui` and the shell assets, plus `/ui/routes`,
`/ui/routes/list`, `/ui/routes/reload`, `/ui/routes/test`, `/ui/metrics`,
`/ui/metrics/data`, `/ui/audit`, `/ui/audit/events`, `/ui/openapi` and their assets for the
views that are active. The contracts the OpenAPI view reads are permitted by the hub's own
chain, not by this one. The database routes management page (`/ui/routes/db`, which creates and deletes
routes) is **not** permitted: it belongs to another plugin and stays under the rules of the
application.

The chain is ordered at `GatewayUiSecurityAutoConfiguration.GATEWAY_UI_CHAIN_ORDER`
(`Ordered.HIGHEST_PRECEDENCE + 300`), ahead of the chains an application usually declares
from `@Order(1)`. Two escape hatches: declare your own bean named
`gatewayUiSecurityWebFilterChain` (the plugin backs off), or turn it off:

```yaml
spring.cloud.gateway.server.webflux:
  ui:
    security-chain-enabled: false
```

> As with any `SecurityWebFilterChain` bean, its presence makes Spring Boot back off from
> its default "everything authenticated" chain. An application that was relying on that
> default must declare its own chains.
