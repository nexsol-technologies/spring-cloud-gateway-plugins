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
* **Home page** &mdash; the landing content rendered inside the shell.

Built with Thymeleaf, Bootstrap, HTMX and plain CSS/JS, served as static resources (no
CDN, offline friendly).

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
`templates/dashboard/fragments/layout.html` (`icon-home`, `icon-plugin`, ...).

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
