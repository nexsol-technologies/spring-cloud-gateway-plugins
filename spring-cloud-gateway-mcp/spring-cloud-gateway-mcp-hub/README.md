# spring-cloud-gateway-mcp-hub

Serves the APIs behind the gateway as MCP tools. Every operation of every contract the
[OpenAPI hub](../../spring-cloud-gateway-hub-openapi/README.md) aggregates becomes a tool on
the same `/mcp` endpoint as the [server](../spring-cloud-gateway-mcp-server/README.md)'s own,
named after its service, and a call of it is an HTTP request **through the gateway**: the
routes, filters, audit, metrics and passive analysis see it as any other request, and it is
made with the `Authorization` the MCP client sent, so the service answers the caller, not the
gateway.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-mcp-hub</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

It brings `spring-cloud-gateway-mcp-server` and `spring-cloud-gateway-hub-openapi` with it, and
needs both switched on.

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.mcp.hub`.

```yaml
spring.cloud.gateway.server.webflux:
  mcp:
    enabled: true
    hub:
      enabled: true          # off by default: it hands an agent every API the hub documents
      write-enabled: false   # GET and HEAD only; true adds the operations that change state
      confirm-writes: true   # those do nothing unless the call carries confirm: true
      timeout: 10s           # one tool call, connection included
      max-response-size: 256KB
  hub-openapi:
    enabled: true
    gateway-uri: http://localhost:8181   # where the tool calls are sent
```

| Property | Default | What it does |
| --- | --- | --- |
| `...mcp.hub.enabled` | `false` | Master switch; needs `...mcp.enabled` and `...hub-openapi.enabled` too |
| `...mcp.hub.write-enabled` | `false` | Registers the operations that change state (`POST`, `PUT`, `PATCH`, `DELETE`) as tools as well |
| `...mcp.hub.confirm-writes` | `true` | Those tools take a `confirm` argument; called without `confirm: true` they describe the request and send nothing |
| `...mcp.hub.timeout` | `10s` | Bounds one tool call |
| `...mcp.hub.max-response-size` | `256KB` | Largest response handed back to the client; a larger one is an error result |
| `...mcp.hub.concurrency` | `4` | Contracts read at the same time when the catalog refreshes |

The bridge works over whichever server Spring AI runs — stateless by default, streamable or
SSE when `spring.ai.mcp.server.protocol` says so — and forwards the caller's `Authorization`
under each.

## The tools

The catalog follows the hub: each time the gateway rebuilds its route table, the contracts of
the documented services are read again and the tools added, removed or replaced accordingly.

| | |
| --- | --- |
| Name | `<service>__<operationId>` in snake case — `service-a__get_book_by_id`; without an `operationId`, the method and the path |
| Title, description | The `summary` and `description` of the operation, then `[GET /books/{id} of service-a]` |
| Arguments | One per parameter, named as in the contract, wherever it goes (path, query, header, cookie); the request body as `body`. The JSON Schema is rebuilt from the contract with the keywords a client needs to build a call |
| Hints | `readOnlyHint` for `GET`/`HEAD`, `destructiveHint` for `DELETE`, `idempotentHint` for `GET`, `PUT` and `DELETE` |
| Result | The body of the response; `HTTP <status>` and the body, as an error result, from `400` up |
| Confirmation | With `confirm-writes` on, an operation that changes state called without `confirm: true` answers what it would send and sends nothing — a confirmation step, not an access control |

A call goes to `gateway-uri` + the prefix the hub advertises for the service + the path, which
is the URL the Swagger UI "Try it out" uses — the gateway routes generated for the same
contract answer it.

## Securing the endpoint

The tools make requests **as the MCP caller**: the `Authorization` header of the call to `/mcp`
is forwarded unchanged, so what the caller may do downstream is what its token allows, under
the gateway's own `AuthorizationToken` and security chains. `/mcp` itself is declared as an
API path by the server plugin; see [its README](../spring-cloud-gateway-mcp-server/README.md#securing-the-endpoint).

## Sample

[gateway-mcp](../../spring-cloud-gateway-samples/gateway/gateway-mcp/README.md), on port 8215,
serves the petstore contract this way; [gateway-full](../../spring-cloud-gateway-samples/gateway/gateway-full/README.md)
serves every service of the registry under the `eureka` profile.
