# spring-cloud-gateway-openapi-validation

Validates the requests and the responses of a route against an OpenAPI contract, so the
contract stops being documentation and becomes something the gateway enforces.

```xml
    <dependencies>
        <dependency>
           <groupId>ch.nexsol-tech.gateway</groupId>
           <artifactId>spring-cloud-gateway-openapi-validation</artifactId>
           <version>${spring-cloud-gateway-plugins.version}</version>
        </dependency>
    </dependencies>
```

## The `OpenapiValidation` filter

Point the filter at a contract and it holds every exchange of that route against it:

```yaml
spring.cloud.gateway.server.webflux:
  routes:
  - id: bookstore
    uri: http://localhost:8080
    predicates:
    - Path=/books/**
    filters:
    - name: OpenapiValidation
      args:
        specUrl: classpath:openapi/bookstore.yaml
```

The shortcut form takes the contract location, optionally followed by the path prefix:

```yaml
    filters:
    - OpenapiValidation=classpath:openapi/bookstore.yaml
```

The contract is read once, on the first request through the route, off the event loop; every
later request is validated against the parsed document with no IO and no parsing. An
`http(s)` URL, a `classpath:` resource and a `file:` path are all accepted — so a contract
served by a Config Server or by the service itself is addressed like any other.

### What is checked

On the request:

* the path and the method resolve to an operation the contract declares — a concrete path
  wins over a templated one, and a path variable never spans a `/`;
* the path, query, header and cookie parameters the contract declares are present when
  required, and each value is read as its declared type before being held against its
  schema, so bounds, patterns and enumerations all apply;
* the body honours the schema of the declared `requestBody`.

On the response:

* the status is one the contract declares, trying the exact code, then the range such as
  `2XX`, then `default`;
* the headers the contract marks as required are there;
* the body honours the schema declared for that status.

### `pathPrefix`: the gateway path is not the contract path

A gateway usually exposes a service under a prefix of its own, while the contract declares
the paths the service itself serves. `pathPrefix` is stripped before a path is matched, so
the two line up:

```yaml
    filters:
    # /book-service/books is validated against the /books operation
    - OpenapiValidation=classpath:openapi/bookstore.yaml,/book-service
```

## Enforcing or reporting, per direction

Each direction is configured on its own, under
`spring.cloud.gateway.server.webflux.openapi-validation`:

```yaml
spring.cloud.gateway.server.webflux:
  openapi-validation:
    request:
      mode: ENFORCE       # a request breaking the contract is denied with 400 Bad Request
    response:
      mode: REPORT        # a response breaking the contract is forwarded, and recorded
```

| Mode | Effect |
| --- | --- |
| `ENFORCE` | A request breaking the contract is denied with `400 Bad Request`; a response breaking it is replaced by `502 Bad Gateway` carrying the violations. |
| `REPORT` | Both are forwarded unchanged. The violations are logged at `WARN`, audited and counted. |
| `OFF` | The direction is not validated at all, and no body of that direction is ever buffered. |

The defaults are `ENFORCE` for the request and `REPORT` for the response, on purpose.
Rejecting a malformed request is what shields the downstream service, and costs nothing that
was working before. A downstream service answering outside its own contract, on the other
hand, is a defect worth measuring rather than an outage worth causing: clients cope with it
today, and turning it into a gateway error would break them. Move the response to `ENFORCE`
once the reports are quiet.

Both directions `OFF` disables the plugin without touching the routes that reference it,
which is what makes it safe to turn off during an incident.

## Bodies, uploads and memory

Validating a body means holding it whole in memory, so the filter decides whether it is
worth it **before** reading anything. A body is only read when it is JSON, uncompressed, and
announces a length within `max-body-size`:

```yaml
spring.cloud.gateway.server.webflux:
  openapi-validation:
    request:
      validate-body: true
      max-body-size: 1MB
```

Everything else streams straight through, untouched and uncounted against the heap:

| Skipped when | Why |
| --- | --- |
| the media type is not JSON | A `multipart/form-data` upload, an `application/octet-stream` stream or an image carries nothing a JSON schema applies to. **A file upload is therefore never buffered, however large it is** — its media type alone settles it, before a single byte is read. |
| the body is compressed | Holding a `Content-Encoding` payload against a schema would mean decompressing it first; left alone, it would be reported as malformed JSON. |
| no `Content-Length` is announced | A chunked body cannot be buffered under a bound, so it is forwarded unread rather than risking the memory of the gateway. |
| the body is larger than `max-body-size` | A large payload must not be able to exhaust the gateway. |

Each skip increments `gateway.openapi.validation.bodies.skipped`, tagged with the reason, so
what is *not* being validated stays visible instead of looking like a clean bill of health.
Setting `validate-body: false` keeps every other check while never buffering anything.

## Metrics

The plugin publishes plain Micrometer meters, which the host application exports to
Prometheus, OpenTelemetry or anything else it already configures a registry for. Nothing
here is specific to a backend.

| Meter | Tags |
| --- | --- |
| `gateway.openapi.validations` | `direction`, `route`, `operation`, `mode`, `outcome` (`valid`/`invalid`) |
| `gateway.openapi.validation.bodies.skipped` | `direction`, `route`, `reason` |
| `gateway.openapi.validation.contracts.unavailable` | `contract` |

Every tag is drawn from a bounded set — the operations of a contract, the routes of the
gateway, a fixed outcome. The violations themselves are never tagged: they belong in the
audit trail and the logs, not in a metric whose cardinality they would blow up.

A gateway with no `MeterRegistry` bean gets a no-op instance, so the filter behaves the same
without the actuator on the classpath.

## Auditing the violations

With [spring-cloud-gateway-audit](../spring-cloud-gateway-audit/README.md) on the classpath,
the outcome is stamped on every audited exchange through the `validation` group, which is on
by default:

```yaml
spring.cloud.gateway.server.webflux:
  audit:
    groups:
      validation: true
```

| Attribute | Value |
| --- | --- |
| `openapi.validation.operation` | The contract operation the exchange was held against, as `GET /books/{id}` |
| `openapi.validation.request.valid` | Whether the request honoured the contract |
| `openapi.validation.request.errors` | The violations, joined by `; ` — only when there are some |
| `openapi.validation.response.valid` | Whether the response honoured the contract |
| `openapi.validation.response.errors` | The violations, joined by `; ` — only when there are some |

Because the audit event already carries the route, the JWT subject and the trace id, a
violation lands in the trail with everything needed to chase it down, in `REPORT` mode as
much as in `ENFORCE`.

The two plugins do not depend on each other: the filter publishes its outcome on the
exchange and the audit group reads it if it is there. Either can be absent. Nothing is added
to an event for an exchange no contract was applied to, so an audited exchange never looks
validated when it was not.

## When the contract cannot be read

A contract that is missing, unreachable or unparseable is a gateway misconfiguration, not a
defect of the traffic. The exchange is **forwarded unvalidated** rather than turned into an
outage, and the failure is logged at `ERROR`, recorded in the audit attributes and counted in
`gateway.openapi.validation.contracts.unavailable`. A failed read is not remembered, so a
contract that becomes reachable again is picked up by the next request without a restart.

If you would rather fail closed, alert on that counter — it is zero on a healthy gateway.

## OpenAPI 3.0 and 3.1

Both are honoured. Bodies are validated by
[networknt/json-schema-validator](https://github.com/networknt/json-schema-validator), which
covers draft-04 through 2020-12: a 3.1 document is validated as JSON Schema 2020-12, a 3.0
one under the older dialect with its `nullable` keyword understood.

Two limits are worth knowing:

* only JSON bodies are held against a schema. Another declared media type is checked for
  being declared at all, since the contract carries no machine-readable schema to apply
  to it;
* a contract read through the resource loader (`classpath:`, `file:`) should be
  self-contained or use absolute references. Only a contract given as an `http(s)` URL keeps
  a base that its relative `$ref` can resolve against.

## Generating the routes from the same contract

[spring-cloud-gateway-routes-openapi](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-openapi/README.md)
turns an OpenAPI contract into gateway routes. Generating routes from a contract does not
validate anything by itself, so the two compose — and a source can attach this filter for
you with `validate: true`:

```yaml
spring.cloud.gateway.server.webflux.routes-openapi:
  enabled: true
  sources:
  - id: bookstore
    uri: http://localhost:8080
    spec-url: classpath:openapi/bookstore.yaml
    path-prefix: /book-service
    validate: true            # attaches OpenapiValidation, reusing the two lines above
```

The filter is given the `spec-url` and `path-prefix` of the source, so they cannot drift
apart, and it is placed ahead of every other filter of the route. Leave `validate` off and
declare the filter in the source `filters` yourself to validate against a different document
than the routes came from.
