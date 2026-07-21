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

When Micrometer is on the classpath, a **Traffic** entry appears (`/ui/metrics`) plotting
every gateway route as a bubble in an interactive chart (ECharts, vendored locally):

* Each bubble is a route, positioned from its request metrics
  (`spring.cloud.gateway.requests`): calls, average and max latency, errors, error rate.
* The X, Y, Z and bubble-size axes are each mapped to a metric from the toolbar; bubble
  colour follows the error rate (green &rarr; red).
* A **3D** switch turns the scatter into a rotatable 3D plot (echarts-gl); **Auto** polls
  the data every 5 seconds.

The chart is fed by `GET /ui/metrics/data` (JSON). The feature stays hidden when no meter
registry is available, and shows an empty state until traffic has flowed through the
gateway (gateway request metrics are enabled by default).

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
