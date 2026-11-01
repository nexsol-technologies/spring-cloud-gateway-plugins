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
  security-chain-enabled: true   # the plugin secures /mcp itself, see "Securing the endpoint"
  security:
    user:                  # the local user accepted over HTTP Basic; no password, no local user
      name: admin
      password:
    oauth2:
      resourceserver.jwt:
        issuer-uri:        # issuer of the Bearer tokens the endpoint accepts
        audiences: []      # what their aud must carry one of
      authorization-servers: []   # published to a client without a token; empty: the issuer above
      scopes: []
```

| Property | Default | What it does |
| --- | --- | --- |
| `...mcp.enabled` | `false` | Master switch; the Spring AI server follows it |
| `...mcp.write-enabled` | `false` | Registers the tools that change the gateway: `save_route`, `delete_route`, `refresh_routes`, `clear_findings` |
| `...mcp.confirm-writes` | `true` | A write tool called without `confirm: true` describes the change and does nothing |
| `...mcp.security-chain-enabled` | `true` | The chain of the plugin over its endpoints; `false` leaves them to the chains of the application |
| `...mcp.security.user.name` | `admin` | Name of the local user |
| `...mcp.security.user.password` | — | Password of the local user; unset, no local user is declared and Basic is checked against the user directory of the application |
| `...mcp.security.user.roles` | `[ADMIN]` | Roles the local user holds |
| `...mcp.security.oauth2.resourceserver.jwt.issuer-uri` | — | Issuer whose Bearer tokens the endpoint accepts; unset, the decoder the application declared |
| `...mcp.security.oauth2.resourceserver.jwt.audiences` | `[]` | Audiences a token of that issuer must carry one of; empty accepts any token of the issuer |
| `...mcp.security.oauth2.authorization-servers` | `[]` | Issuers published to a client without a token; empty publishes the issuer above, else the console's, else `spring.security.oauth2.resourceserver.jwt.issuer-uri`, and with none nothing is published |
| `...mcp.security.oauth2.resource` | — | The URL of the endpoint as the clients reach it; unset, it is derived from each request |
| `...mcp.security.oauth2.scopes` | `[]` | Scopes a client may ask for, published as `scopes_supported` |

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

`/mcp` hands out the route table and the findings in one call. With Spring Security on the
classpath the plugin secures it itself: a principal is required, no CSRF token, and an
anonymous call is answered `401`. Two credentials pass:

| Credential | Validated against |
| --- | --- |
| HTTP Basic | the local user of the endpoint, `...mcp.security.user`, held by this chain alone; without a password, the user directory of the application: `spring.security.user`, or the `ReactiveUserDetailsService` it declares |
| Bearer token | `...mcp.security.oauth2.resourceserver.jwt.issuer-uri`, with `...audiences` when the token must have been issued for this gateway; unset, the `ReactiveJwtDecoder` the application declared, if any |

```yaml
spring.cloud.gateway.server.webflux.mcp.security:
  user:
    name: agent
    password: "{bcrypt}$2a$10$..."    # a clear-text value is encoded at start-up
  oauth2.resourceserver.jwt:
    issuer-uri: https://your-idp.example.com/realms/master
    audiences: [https://gateway.example.com/mcp]
```

The issuer is asked for its keys on the first token that arrives, never at start-up. The chain
covers the endpoints of the protocol in force and the metadata of
[OAuth discovery](#oauth-discovery), which stays open; everything else is left to the chains of
the application. `...mcp.security-chain-enabled=false` turns it off, and a bean named
`gatewayMcpSecurityWebFilterChain` replaces it.

With the [console](../../spring-cloud-gateway-ui/README.md) present, its chain governs `/mcp`
instead, ordered ahead of this one: the endpoint follows
[the mode of the console](../../spring-cloud-gateway-ui/README.md#the-endpoints-of-the-other-plugins),
and a Bearer token is the console's
[issuer](../../spring-cloud-gateway-ui/README.md#a-bearer-token). Without Spring Security at
all, nothing secures the endpoint: do not enable the plugin on anything reachable.

## OAuth discovery

Once an issuer is known — the endpoint's own, else the console's, else the application's, or
the ones named under `...mcp.security.oauth2.authorization-servers` — a client without a token is told
where to obtain one, the way the MCP specification says:

1. The `401` of `/mcp` carries `WWW-Authenticate: Bearer resource_metadata="https://<gateway>/.well-known/oauth-protected-resource/mcp"`.
2. That document (RFC 9728) names the `resource` a token must be issued for, the
   `authorization_servers`, and the `scopes_supported` when scopes are configured. It is
   served open, at the root well-known path as well, and the chain answering the `401` is
   whichever governs `/mcp`.
3. The client reads the issuer's configuration, opens the browser on its login (PKCE, a
   redirect to a port of its own), and from then on sends and renews the token itself.

The token is validated by the chain governing `/mcp`: the plugin's, under
`...mcp.security.oauth2.resourceserver.jwt.issuer-uri` plus `...audiences`, or the console's under
`...ui.security.oauth2.resourceserver.jwt.issuer-uri` plus `...audiences`, so that a token the
issuer handed out for another service is refused.

### What the issuer must allow

The client identifies itself to the issuer in one of three ways: by a URL it publishes its own
metadata at (a Client ID Metadata Document, which Claude Code, VS Code and Claude Desktop do),
by registering itself (dynamic client registration, RFC 7591), or with a client declared for
it. On a Keycloak realm, following
[Keycloak as an MCP authorization server](https://www.keycloak.org/securing-apps/mcp-authz-server):

**Client ID Metadata Documents** — start Keycloak with `--features=cimd,resource-indicators`,
then *Realm settings › Client policies*:

| Step | Setting | Value |
| --- | --- | --- |
| *Profiles › Create client profile*, executor `client-id-metadata-document` | Trusted domains | `claude.ai`, `localhost`, `127.0.0.1` for the Claude clients; `vscode.dev`, `127.0.0.1`, `code.visualstudio.com` for VS Code |
| | Restrict same domain | Off: the redirect URI is on `localhost`, the client id on `claude.ai` |
| | Accept Public Client with Confidential-only Grant Types | On: Claude Desktop lists the JWT bearer grant |
| | Only Allow Confidential Client | Off |
| | Resource indicator allow list | the `resource` the gateway publishes, `https://gateway.example.com/mcp` |
| *Policies › Create client policy*, condition `client-id-uri` | URI scheme / Trusted domains | `https`; `claude.ai` and `vscode.dev` |
| | Associated client profiles | the profile above |

One policy and one profile for all of them: two profiles trusting `claude.ai` reject each
other's client.

**A client declared for the IDE** — *Clients › Create client*:

| Setting | Value |
| --- | --- |
| Client type / Client ID | OpenID Connect, e.g. `mcp-ide` |
| Client authentication | Off: a public client, no secret on a developer's machine |
| Standard flow | On; the other flows off |
| Valid redirect URIs | Claude Code: `http://localhost:<port>/callback`, with the port fixed by `--callback-port`; Gemini CLI: `http://localhost:<port>/oauth/callback`, with the port fixed by `oauth.redirectUri`; otherwise the URI the client names in its documentation |
| Advanced › Proof Key for Code Exchange Code Challenge Method | `S256` |

Then the client is told which id to use:

```console
claude mcp add --transport http --client-id mcp-ide --callback-port 8765 \
  gateway https://gateway.example.com/mcp
```

In VS Code, `oauth.clientId` in `.vscode/mcp.json` does the same; a confidential client gets its
secret through the *Set Client Secret* action above that line, never in the file. Gemini CLI
publishes no metadata document of its own: it registers dynamically, or is given the client
in `~/.gemini/settings.json` and authenticates with `/mcp auth gateway`:

```json
"mcpServers": {
  "gateway": {
    "httpUrl": "https://gateway.example.com/mcp",
    "oauth": { "enabled": true, "clientId": "mcp-ide", "redirectUri": "http://localhost:8765/oauth/callback",
               "audiences": ["https://gateway.example.com/mcp"] }
  }
}
```

**Dynamic registration instead** — *Realm settings › Client registration › Anonymous access
policies*: the *Trusted Hosts* policy lists the hosts allowed to register (the developers'
network), the *Allowed Client Scopes* policy the scopes a registered client may ask for. With
nothing listed, Keycloak refuses anonymous registration and the client reports that the
authorization server does not support it.

**The audience** — a Keycloak token carries `aud` = the client ids it was issued to, not the
gateway, and `audiences` must hold what the token will carry. Two ways to put the gateway there:

- With `resource-indicators` on, the client sends `resource=https://gateway.example.com/mcp`
  (RFC 8707) and Keycloak puts that value, alone, in `aud` — provided it knows it: declare the
  gateway as a client with the `resource_url` attribute set to that URL (Admin REST API or realm
  import), and an *Audience* mapper with *Included Client Audience* on the IDE client. A
  `resource` Keycloak does not know is refused with `invalid_target`, so publish exactly the
  URL the clients use: set `...mcp.security.oauth2.resource` rather than letting it be derived
  behind a proxy.
- Without it, Keycloak ignores `resource`: create optional client scopes (`mcp:tools`, say),
  each with an *Audience* mapper whose *Included Custom Audience* is that URL, publish them
  under `...mcp.security.oauth2.scopes` so the client asks for them, and list them in the
  *Allowed Client Scopes* of the registration policies.

Either way, `audiences` of the plugin — or of the console, whichever governs `/mcp` — is the
URL. Without a mapper, leave `audiences` empty or the gateway refuses every token of the realm.

## Connecting a client

Any client speaking MCP over Streamable HTTP. With [OAuth discovery](#oauth-discovery) on,
nothing but the URL: the client obtains its token on the first `401`.

```console
claude mcp add --transport http gateway https://gateway.example.com/mcp
```

Without it, the credentials go in a header:

```console
claude mcp add --transport http gateway https://gateway.example.com/mcp \
  --header "Authorization: Bearer <token>"
```

The [sample](#sample) shows the HTTP Basic variant, and the same calls over `curl`.

## Sample

[gateway-mcp](../../spring-cloud-gateway-samples/gateway/gateway-mcp/README.md), on port 8215.
The three [ahead-of-time samples](../../spring-cloud-gateway-samples/README.md#ahead-of-time-builds)
carry the plugin as well.
