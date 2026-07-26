# spring-cloud-gateway-ui

This plugin provides a Spring Boot Admin-like web UI shell for Spring Cloud Gateway: a
home page served under `/ui` with a collapsible side menu. Each gateway plugin lights up
its own menu entry automatically when it is present on the classpath.

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
| Audit | `/ui/audit` | `spring-cloud-gateway-audit-core` is present |

## Home page

An overview of the gateway rather than a welcome text: the uptime, one tile per figure
contributed by the active views (routes and their sources, calls, average latency, server
errors, audited exchanges) and a link to every view that lit up.

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

**Refresh** re-reads the sources; **Reload route table** additionally publishes a
`RefreshRoutesEvent`, asking the gateway to rebuild its route table from the current
definitions &mdash; exactly what the gateway actuator endpoint does. Neither button re-reads
a remote source: a database, file or Config Server source reloads through its own plugin
(a file watch, a poll, `/actuator/refresh`).

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

## Traffic view

When Micrometer is on the classpath, a **Traffic** entry appears (`/ui/metrics`), built
from the gateway request metrics (`spring.cloud.gateway.requests`): calls, average and max
latency, errors and error rate per route. The page reads top to bottom &mdash; summary,
then map, then the exact numbers.

**Summary** &mdash; routes called, total calls, weighted average latency, total 5xx.

**Map** &mdash; one bubble per route (ECharts, vendored locally). Rather than exposing raw
axes, the chart answers a named question that also picks the metrics:

| Question | Reads as |
| --- | --- |
| Where should I optimise? | calls &times; avg latency &mdash; top-right is busier *and* slower than the median route |
| Where does it break? | calls &times; error rate &mdash; top-right fails on traffic that matters |
| Which routes spike? | avg &times; max latency &mdash; outliers have a worst case far above their average |
| Custom&hellip; | re-opens the raw X / Y / bubble-size pickers |

Dashed lines mark the median of both axes and the four quadrants are labelled in place, so
a bubble's position is readable without a legend. Bubble colour is the error rate
(green &rarr; red), and each bubble is labelled with its route id. The **3D** switch adds a
third metric on a rotatable Z axis (echarts-gl); **Auto** polls every 5 seconds.

**All routes** &mdash; the same data as a sortable table (click any column) for the exact
figures, with the error rate as a colour-coded badge.

The view is fed by `GET /ui/metrics/data` (JSON). It stays hidden when no meter registry is
available, and shows an empty state until traffic has flowed through the gateway (gateway
request metrics are enabled by default).

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
`icon-target`, `icon-chart`, `icon-list`).

The built-in entries are ordered `home` (0), `Routes` (5), `Database routes` (10),
`Route tester` (15), `Traffic` (20) and `Audit` (30), leaving room for your own in between.

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
