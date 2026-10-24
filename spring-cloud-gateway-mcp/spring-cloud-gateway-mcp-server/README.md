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
  enabled: true   # off by default: it hands out the configuration and the findings of the gateway
```

| Property | Default | What it does |
| --- | --- | --- |
| `...mcp.enabled` | `false` | Master switch; the Spring AI server follows it |

The server itself is Spring AI's, configured under `spring.ai.mcp.server`. The plugin sets the
values below as defaults; a value the application sets wins.

| Property | Set to | Why |
| --- | --- | --- |
| `spring.ai.mcp.server.enabled` | `...mcp.enabled` | Spring AI's own default is `true`: without this, a gateway carrying the jar with the plugin off would serve an unauthenticated, empty `/mcp` |
| `spring.ai.mcp.server.protocol` | `STATELESS` | No session between two calls, so a fleet behind a load balancer needs no affinity |
| `spring.ai.mcp.server.type` | `ASYNC` | The tools are reactive; a `SYNC` server ignores them silently |
| `spring.ai.mcp.server.name` | `spring.application.name` | What the client shows |
| `spring.ai.mcp.server.instructions` | A sentence on what the server holds | Guidance the client reads at connection |

The endpoint is `spring.ai.mcp.server.streamable-http.mcp-endpoint`, `/mcp` by default; the
plugin declares as secured whatever it is set to.

## The tools

Each group registers when its plugin is on the classpath and wired, so a client only ever
sees what the gateway runs. Every tool declares itself read-only.

| Tool | Returns | Registers when |
| --- | --- | --- |
| `list_routes` | Every route definition: id, target URI, order, predicates and filters with their arguments, metadata, and the source it came from | always |
| `get_route` | One route definition, by id | always |
| `list_filter_factories` | The filters a route may name, each with the fields of its shorthand form, in order | always |
| `list_predicate_factories` | The same, for the predicates | always |
| `route_traffic` | Requests, latency and error rates per route, with the coverage the figures were computed over | [metrics](../../spring-cloud-gateway-metrics/README.md) has put a route source in the context |
| `gateway_instances` | Uptime, JVM, CPU, event loops and connection pools of each instance | the same, with an instance source |
| `list_findings` | The OWASP findings raised on the live traffic, narrowed by severity or route | [pentest-passive](../../spring-cloud-gateway-pentest/spring-cloud-gateway-pentest-passive/README.md) is enabled |
| `finding_summary` | Their counts, by severity and by category | the same |
| `route_security_scores` | A score and a grade per route | the same |
| `scan_coverage` | The OWASP categories the analyser observes | the same |
| `export_findings` | The JSON, SARIF or HTML report the console exports | the same |

## The prompts

| Prompt | Argument | What it asks the client to do |
| --- | --- | --- |
| `diagnose-route` | `routeId` | Read the route, its traffic and its findings, and conclude on what to change |
| `security-review` | — | Summarise the findings worst first, name the weakest routes, say what was not observed |

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
The plugin is not part of the ahead-of-time samples yet.
