# gateway-openapi-validation

Exercises [spring-cloud-gateway-openapi-validation](../../../spring-cloud-gateway-openapi-validation/README.md)
— port `8212`: the `OpenapiValidation` filter holds the traffic of a route against the
bookstore contract shipped in `src/main/resources/openapi/bookstore.yaml`.

## Run it

```console
mvn spring-boot:run
```

No backend is needed to see the request side: a request that breaks the contract is denied
before it is ever forwarded.

## What to look at

The gateway exposes the contract under `/book-service`, while the contract itself declares
`/books` and `/books/{id}`. `pathPrefix` strips the prefix before a path is matched, and
`StripPrefix=1` strips it again before the request is forwarded — so the backend still receives
the path its own contract declares. Requests are enforced and responses only reported on, which
are the defaults.

The first four calls never reach a backend, so they answer on their own:

```console
# 400: 'page' is declared as an integer
curl -i 'localhost:8212/book-service/books?page=first'

# 400: 'status' is outside the declared enumeration
curl -i 'localhost:8212/book-service/books?status=lost'

# 400: the contract declares no such operation
curl -i 'localhost:8212/book-service/authors'

# 400: 'author' is required by the Book schema
curl -i -XPOST localhost:8212/book-service/books \
     -H 'content-type: application/json' -d '{"title":"Dune"}'
```

These honour the contract and are forwarded, so they need something listening on `:8080`:

```console
curl -i 'localhost:8212/book-service/books?page=1&status=available'

curl -i -XPOST localhost:8212/book-service/books \
     -H 'content-type: application/json' -d '{"title":"Dune","author":"Herbert"}'
```

## The counters

Every outcome is counted, and the actuator exposes them:

```console
curl -s localhost:8212/actuator/metrics/gateway.openapi.validations
curl -s localhost:8212/actuator/metrics/gateway.openapi.validation.bodies.skipped
```

These are plain Micrometer meters, so a real deployment exports them to Prometheus or
OpenTelemetry through whatever registry it already configures.

## Uploads are never buffered

The contract declares `POST /books/{id}/cover` as a `multipart/form-data` upload. A payload
of that media type carries nothing a JSON schema applies to, so the filter never reads it
into memory, however large it is — it decides on the media type alone, before a single byte
is read. The skip shows up under `gateway.openapi.validation.bodies.skipped` with
`reason=not_json`.

## The audit trail

`spring-cloud-gateway-audit-core` is on the classpath, so with the `Audit` filter on a route
each event also carries `openapi.validation.operation`,
`openapi.validation.request.valid` and, when there were violations,
`openapi.validation.request.errors` — alongside the route, the principal and the trace id.
