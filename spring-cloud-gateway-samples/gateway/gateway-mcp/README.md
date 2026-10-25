# gateway-mcp

Exercises [spring-cloud-gateway-mcp-server](../../../spring-cloud-gateway-mcp/spring-cloud-gateway-mcp-server/README.md)
and [spring-cloud-gateway-mcp-hub](../../../spring-cloud-gateway-mcp/spring-cloud-gateway-mcp-hub/README.md)
— port `8215`, with the metrics and passive scan plugins present, so every group of tools is
registered, and the petstore contract documented by the hub, so its operations are tools too.

## Run it

```console
mvn spring-boot:run
```

No infrastructure is needed. The endpoint sits behind the console's login, `admin` / `admin`,
and an MCP client sends the same credentials as HTTP Basic:

```console
claude mcp add --transport http gateway http://localhost:8215/mcp \
  --header "Authorization: Basic YWRtaW46YWRtaW4="
```

Then ask Claude Code *"list the routes of the gateway"*, or run its `diagnose-route` prompt.
Without a client, the same over curl — one JSON-RPC call per tool:

```console
curl -s -u admin:admin http://localhost:8215/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'

curl -s -u admin:admin http://localhost:8215/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_routes","arguments":{}}}' \
  | jq -r '.result.content[0].text' | jq
```

Send some traffic through the gateway first, and the traffic and finding tools have something
to say:

```console
curl http://localhost:8215/httpbin/get
```

The petstore tools need the internet: the contract is read from `petstore3.swagger.io`, and a
call of `petstore__get_pet_by_id` goes through the `/pet/{petId}` route generated from it.
Without a connection the listing holds the gateway's own tools and a warning says the contract
could not be read.

## What to look at

| Url | What it shows |
| --- | --- |
| `POST http://localhost:8215/mcp` | The MCP endpoint: `tools/list`, `tools/call`, `prompts/list`, `prompts/get` |
| http://localhost:8215/ui/routes | The routes `list_routes` returns, as the console shows them |
| http://localhost:8215/ui/metrics | The figures `route_traffic` returns |
| http://localhost:8215/ui/passive-scan | The findings `list_findings` returns |
| http://localhost:8215/swagger-ui.html | The petstore contract the `petstore__*` tools come from |

## Profiles

None. The sample routes to `httpbin` and to `service-a` (`:8080`), which need not be up: a
route the gateway cannot reach still has a definition, traffic figures and findings.
