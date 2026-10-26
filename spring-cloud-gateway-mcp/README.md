# spring-cloud-gateway-mcp

Serves the gateway to an agent over the Model Context Protocol. What the console shows a
person, an MCP client reads as tools — the routes and where each came from, their traffic, the
OWASP findings raised on them — and, with the hub module, every API the gateway fronts, each
call routed through the gateway as the caller. One endpoint, governed like every other
endpoint of the plugins.

## Modules

| Module | What it does |
| --- | --- |
| [`-server`](spring-cloud-gateway-mcp-server/README.md) | The MCP server, built on Spring AI: the tools and prompts, one group per plugin present on the classpath |
| [`-hub`](spring-cloud-gateway-mcp-hub/README.md) | Every operation of every contract the OpenAPI hub aggregates as a tool, called through the gateway as the MCP caller |

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-mcp-server</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## Sample

[gateway-mcp](../spring-cloud-gateway-samples/gateway/gateway-mcp/README.md), on port 8215.
