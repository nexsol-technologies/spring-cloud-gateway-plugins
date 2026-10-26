# spring-cloud-gateway-mcp-server

An MCP server inside the gateway, built on Spring AI. An agent connecting to `/mcp` lists the
routes and the source each came from, the filter and predicate factories a route may name,
and — when the plugin is present — the traffic figures of the metrics plugin and the findings
of the passive analyser. Read-only: no tool changes the gateway.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-mcp-server</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.mcp`.

```yaml
spring.cloud.gateway.server.webflux.mcp:
  enabled: true          # off by default: it hands out the configuration and the findings of the gateway
  write-enabled: false   # true adds the tools that change the gateway
  confirm-writes: true   # those tools do nothing unless the call carries confirm: true
```

| Property | Default | What it does |
| --- | --- | --- |
| `...mcp.enabled` | `false` | Master switch; the Spring AI server follows it |
| `...mcp.write-enabled` | `false` | Registers the tools that change the gateway: `save_route`, `delete_route`, `refresh_routes`, `clear_findings` |
| `...mcp.confirm-writes` | `true` | A write tool called without `confirm: true` describes the change and does nothing |

The server itself is Spring AI's, configured under `spring.ai.mcp.server`. The plugin gives
these properties the defaults below; a value the application sets wins.

| Property | Default | What it does |
| --- | --- | --- |
| `spring.ai.mcp.server.enabled` | the value of `...mcp.enabled` | Runs the Spring AI server; Spring AI alone would run it whenever the jar is present |
| `spring.ai.mcp.server.protocol` | `STATELESS` | `STATELESS` \| `STREAMABLE` \| `SSE`; stateless keeps no session between two calls |
| `spring.ai.mcp.server.type` | `ASYNC` | `ASYNC` \| `SYNC`; a `SYNC` server registers none of the tools, which are reactive |
| `spring.ai.mcp.server.name` | `spring.application.name` | The name the client shows |
| `spring.ai.mcp.server.instructions` | a sentence on what the server holds | Guidance the client reads at connection |

The plugin declares as secured the endpoints of the protocol in force: `/mcp`
(`spring.ai.mcp.server.streamable-http.mcp-endpoint`) for `STATELESS` and `STREAMABLE`, `/sse`
and `/mcp/message` (`spring.ai.mcp.server.sse-endpoint`, `...sse-message-endpoint`) for `SSE`.

## The tools

Each group registers when its plugin is on the classpath and wired, so a client only ever
sees what the gateway runs. Every tool declares itself read-only.

| Tool | Returns | Registers when |
| --- | --- | --- |
| `list_routes` | Every route definition: id, target URI, order, predicates and filters with their arguments, metadata, and the source it came from — plus the sources that could not be read, whose routes are missing | always |
| `get_route` | One route definition, by id | always |
| `list_filter_factories` | The filters a route may name, each with the fields of its shorthand form, in order | always |
| `list_predicate_factories` | The same, for the predicates | always |
| `match_request` | Which route a described request (method, path, headers) would take, each predicate of each route reported as matched or not | always |
| `route_traffic` | Requests, latency and error rates per route, with the coverage the figures were computed over | [metrics](../../spring-cloud-gateway-metrics/README.md) has put a route source in the context |
| `gateway_instances` | Uptime, JVM, CPU, event loops and connection pools of each instance | the same, with an instance source |
| `list_findings` | The OWASP findings raised on the live traffic, narrowed by severity or route | [pentest-passive](../../spring-cloud-gateway-pentest/spring-cloud-gateway-pentest-passive/README.md) is enabled |
| `finding_summary` | Their counts, by severity and by category | the same |
| `route_security_scores` | A score and a grade per route | the same |
| `scan_coverage` | The OWASP categories the analyser observes | the same |
| `export_findings` | The JSON, SARIF or HTML report the console exports | the same |
| `save_route` ✎ | Creates or replaces a route in the writable source (the database with routes-database, the gateway's in-memory repository otherwise) and rebuilds the route table | `write-enabled` |
| `delete_route` ✎ | Deletes a route from the writable source and rebuilds the route table | `write-enabled` |
| `refresh_routes` ✎ | Rebuilds the route table from every source, as `/actuator/gateway/refresh` does | `write-enabled` |
| `clear_findings` ✎ | Discards the retained findings | `write-enabled` and pentest-passive |

✎ — changes the gateway. With `confirm-writes` on, the tool answers what it would do and does
nothing until the call carries `confirm: true`; a client that follows the `destructiveHint`
asks its user before that. A confirmation step, not an access control: whoever may call `/mcp`
may confirm.

## The resources

What a client can read into its context without a tool call, as JSON.

| Resource | Content |
| --- | --- |
| `gateway://routes` | What `list_routes` answers |
| `gateway://routes/{routeId}` | What `get_route` answers |
| `gateway://factories` | The filter and predicate factories |
| `gateway://findings` | What `list_findings` answers without a filter (pentest-passive) |

## The prompts

| Prompt | Argument | What it asks the client to do |
| --- | --- | --- |
| `diagnose-route` | `routeId` | Read the route, check its filters and predicates against the factories, read its traffic and its findings, and conclude on what to change |
| `security-review` | — | Count the findings, list them in full, name the weakest routes, say what was not observed |

## Securing the endpoint

`/mcp` hands out the route table and the findings in one call. The plugin declares it as an API
path — a principal is always required, no CSRF token — but a declaration is only enforced by a
security chain: the console's, once `...ui.security.mode` is set, or one of your own. The
console's chain answers an anonymous call `401` and accepts HTTP Basic against the user it
holds, or a Bearer token once an issuer is configured; see
[the endpoints of the other plugins](../../spring-cloud-gateway-ui/README.md#the-endpoints-of-the-other-plugins)
and [a Bearer token](../../spring-cloud-gateway-ui/README.md#a-bearer-token). Put the endpoint
behind one of them before enabling the plugin on anything reachable.

## Connecting a client

Any client speaking MCP over Streamable HTTP, with the credentials in a header:

```console
claude mcp add --transport http gateway https://gateway.example.com/mcp \
  --header "Authorization: Bearer <token>"
```

The [sample](#sample) shows the HTTP Basic variant, and the same calls over `curl`.

## Sample

[gateway-mcp](../../spring-cloud-gateway-samples/gateway/gateway-mcp/README.md), on port 8215.
The three [ahead-of-time samples](../../spring-cloud-gateway-samples/README.md#ahead-of-time-builds)
carry the plugin as well.
