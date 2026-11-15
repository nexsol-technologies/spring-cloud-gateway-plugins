# gateway-mcp

Exercises [spring-cloud-gateway-mcp-server](../../../spring-cloud-gateway-mcp/spring-cloud-gateway-mcp-server/README.md)
and [spring-cloud-gateway-mcp-hub](../../../spring-cloud-gateway-mcp/spring-cloud-gateway-mcp-hub/README.md)
— port `8215`, with the metrics and passive scan plugins present, so every group of tools is
registered, and the petstore contract documented by the hub, so its operations are tools too.

## Run it

```console
mvn spring-boot:run
```

No infrastructure, and no console: the plugin's own chain secures `/mcp` against the local user
it is given, `admin` / `admin` under `...mcp.security.user`, asks for the `ADMIN` role that user
holds (`...mcp.security.required-roles`), and an MCP client sends the user as HTTP Basic:

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

### Who may call `/mcp`

The chain asks for a role, not only for a user. Run the sample with the same user holding
another one, and the call that answered `200` answers `403`:

```console
mvn spring-boot:run \
  -Dspring-boot.run.arguments=--spring.cloud.gateway.server.webflux.mcp.security.user.roles=READER

curl -s -o /dev/null -w '%{http_code}\n' -u admin:admin http://localhost:8215/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### What a service receives

With [service-a](../../service-a) started first on `:8080`, its operations are tools too.
`service-a__whoami` answers the scheme of the credentials the service received:

```console
curl -s -u admin:admin http://localhost:8215/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"service-a__whoami","arguments":{}}}' \
  | jq -r '.result.content[0].text'
```

It prints `{"authorization":"none"}`: the Basic credentials are those of `/mcp` and are not
relayed, where a Bearer token would be. The contract of service-a declares the `Authorization`
header as a parameter of that operation, and the tool still takes no such argument — compare
`curl http://localhost:8080/v3/api-docs | jq '.paths."/whoami"'` with the `inputSchema` that
`tools/list` gives for `service-a__whoami`.
Without a connection the listing holds the gateway's own tools and a warning says the contract
could not be read.

## What to look at

| Url | What it shows |
| --- | --- |
| `POST http://localhost:8215/mcp` | The MCP endpoint: `tools/list`, `tools/call`, `prompts/list`, `prompts/get` |
| http://localhost:8215/swagger-ui.html | The petstore contract the `petstore__*` tools come from |
| http://localhost:8215/actuator/gateway/routes | The routes `list_routes` returns |

The console is deliberately absent: [gateway-full](../gateway-full/README.md) runs the same
plugin next to it, where `/mcp` is given an issuer and keeps a chain of its own, apart from
the login of the console.

## Profiles

None. The sample routes to `httpbin` and to `service-a` (`:8080`), which need not be up: a
route the gateway cannot reach still has a definition, traffic figures and findings. Its
`service-a__*` tools are only there when service-a was up as the gateway started.

Switching the server off with `spring.cloud.gateway.server.webflux.mcp.enabled=false` leaves
the bridge on in this configuration: the gateway starts and serves no tool.
