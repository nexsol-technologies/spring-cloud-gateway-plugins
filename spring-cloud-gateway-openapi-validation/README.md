# spring-cloud-gateway-openapi-validation

Validates the requests and the responses of a route against an OpenAPI contract, so the
contract stops being documentation and becomes something the gateway enforces.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-openapi-validation</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

## The OpenapiValidation filter

Point the filter at a contract and it holds every exchange of that route against it. The
shorthand takes the contract location, optionally followed by the path prefix:

```yaml
spring.cloud.gateway.server.webflux.routes:
  - id: bookstore
    uri: http://localhost:8080
    predicates:
      - Path=/book-service/books/**
    filters:
      # /book-service/books is validated against the /books operation of the contract.
      - OpenapiValidation=classpath:openapi/bookstore.yaml,/book-service
      # or, in the long form:
      # - name: OpenapiValidation
      #   args:
      #     specUrl: classpath:openapi/bookstore.yaml
      #     pathPrefix: /book-service
```

| Argument | Default | What it does |
| --- | --- | --- |
| `specUrl` | — | Contract location: `classpath:`, `file:` or an `http(s)` URL |
| `pathPrefix` | — | Prefix stripped before a path is matched against the contract |

A gateway usually exposes a service under a prefix of its own, while the contract declares the
paths the service itself serves — `pathPrefix` is what lines the two up.

The contract is read once, on the first request through the route, off the event loop; every
later request is validated against the parsed document with no IO and no parsing.

**On the request:** the path and method resolve to a declared operation (a concrete path wins
over a templated one, and a path variable never spans a `/`); the declared path, query, header
and cookie parameters are present when required, each value read as its declared type before
being held against its schema; the body honours the schema of the declared `requestBody`.

**On the response:** the status is one the contract declares (exact code, then the range such
as `2XX`, then `default`); the required headers are there; the body honours the schema
declared for that status.

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.openapi-validation`, and each
direction is configured on its own.

```yaml
spring.cloud.gateway.server.webflux.openapi-validation:
  request:
    mode: ENFORCE        # a request breaking the contract is denied with 400
    validate-body: true
    max-body-size: 1MB
  response:
    mode: REPORT         # a response breaking the contract is forwarded, and recorded
```

| Property | Default | What it does |
| --- | --- | --- |
| `...openapi-validation.request.mode` | `ENFORCE` | `ENFORCE`, `REPORT` or `OFF` |
| `...openapi-validation.request.validate-body` | `true` | Whether the body is held against its schema at all |
| `...openapi-validation.request.max-body-size` | `1MB` | Above this announced length, the body is forwarded unread |
| `...openapi-validation.response.mode` | `REPORT` | Same three values |
| `...openapi-validation.response.validate-body` | `true` | As above, for the response |
| `...openapi-validation.response.max-body-size` | `1MB` | As above, for the response |

| Mode | Effect |
| --- | --- |
| `ENFORCE` | A request breaking the contract is denied with `400 Bad Request`; a response breaking it is replaced by `502 Bad Gateway` carrying the violations |
| `REPORT` | Both are forwarded unchanged; the violations are logged at `WARN`, audited and counted |
| `OFF` | The direction is not validated at all, and no body of that direction is ever buffered |

The defaults differ on purpose. Rejecting a malformed request shields the downstream service
and costs nothing that was working before. A downstream service answering outside its own
contract is a defect worth measuring rather than an outage worth causing: clients cope with it
today, and turning it into a gateway error would break them. Move the response to `ENFORCE`
once the reports are quiet.

Both directions `OFF` disables the plugin without touching the routes that reference it, which
is what makes it safe to turn off during an incident.

## Bodies, uploads and memory

Validating a body means holding it whole in memory, so the filter decides whether it is worth
it **before** reading anything. A body is only read when it is JSON, uncompressed, and
announces a length within `max-body-size`. Everything else streams straight through:

| Skipped when | Why |
| --- | --- |
| The media type is not JSON | A `multipart/form-data` upload, an octet stream or an image carries nothing a JSON schema applies to. **A file upload is therefore never buffered, however large** — its media type alone settles it, before a single byte is read |
| The body is compressed | Holding a `Content-Encoding` payload against a schema would mean decompressing it first; left alone it would be reported as malformed JSON |
| No `Content-Length` is announced | A chunked body cannot be buffered under a bound, so it is forwarded unread rather than risking the memory of the gateway |
| The body is larger than `max-body-size` | A large payload must not be able to exhaust the gateway |

Each skip increments `gateway.openapi.validation.bodies.skipped`, tagged with the reason, so
what is *not* being validated stays visible instead of looking like a clean bill of health.
`validate-body: false` keeps every other check while never buffering anything.

## Metrics

Plain Micrometer meters, which the host application exports through whatever registry it
already configures.

| Meter | Tags |
| --- | --- |
| `gateway.openapi.validations` | `direction`, `route`, `operation`, `mode`, `outcome` (`valid`/`invalid`) |
| `gateway.openapi.validation.bodies.skipped` | `direction`, `route`, `reason` |
| `gateway.openapi.validation.contracts.unavailable` | `contract` |

Every tag is drawn from a bounded set. The violations themselves are never tagged: they belong
in the audit trail and the logs, not in a metric whose cardinality they would blow up. A
gateway with no `MeterRegistry` bean gets a no-op instance.

## Auditing the violations

With [spring-cloud-gateway-audit](../spring-cloud-gateway-audit/README.md) on the classpath,
the outcome is stamped on every audited exchange through the `validation` group, on by default:

| Attribute | Value |
| --- | --- |
| `openapi.validation.operation` | The operation the exchange was held against, as `GET /books/{id}` |
| `openapi.validation.request.valid` | Whether the request honoured the contract |
| `openapi.validation.request.errors` | The violations, joined by `; ` — only when there are some |
| `openapi.validation.response.valid` | Whether the response honoured the contract |
| `openapi.validation.response.errors` | The violations, joined by `; ` — only when there are some |

The audit event already carries the route, the JWT subject and the trace id, so a violation
lands in the trail with everything needed to chase it down — in `REPORT` mode as much as in
`ENFORCE`. Neither plugin depends on the other, and nothing is added for an exchange no
contract was applied to.

## When the contract cannot be read

A contract that is missing, unreachable or unparseable is a gateway misconfiguration, not a
defect of the traffic. The exchange is **forwarded unvalidated** rather than turned into an
outage, and the failure is logged at `ERROR`, recorded in the audit attributes and counted in
`gateway.openapi.validation.contracts.unavailable`. A failed read is not remembered, so a
contract that becomes reachable again is picked up by the next request without a restart.

To fail closed, alert on that counter — it is zero on a healthy gateway.

## OpenAPI 3.0 and 3.1

Both are honoured. Bodies are validated by
[networknt/json-schema-validator](https://github.com/networknt/json-schema-validator), which
covers draft-04 through 2020-12: a 3.1 document is validated as JSON Schema 2020-12, a 3.0 one
under the older dialect with its `nullable` keyword understood.

Two limits worth knowing:

* only JSON bodies are held against a schema — another declared media type is checked for being
  declared at all, the contract carrying no machine-readable schema to apply to it;
* a contract read through the resource loader (`classpath:`, `file:`) should be self-contained
  or use absolute references. Only a contract given as an `http(s)` URL keeps a base its
  relative `$ref` can resolve against.

## Generating the routes from the same contract

[routes-openapi](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md)
turns a contract into gateway routes, and can attach this filter for you:

```yaml
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources:
    - id: bookstore
      uri: http://localhost:8080
      spec-url: classpath:openapi/bookstore.yaml
      path-prefix: /book-service
      validate: true      # attaches OpenapiValidation, reusing the two lines above
```

The filter is given the `spec-url` and `path-prefix` of the source, so they cannot drift apart,
and it is placed ahead of every other filter of the route.

## Sample

[gateway-openapi-validation](../spring-cloud-gateway-samples/gateway/gateway-openapi-validation/README.md)
— port `8212`.
