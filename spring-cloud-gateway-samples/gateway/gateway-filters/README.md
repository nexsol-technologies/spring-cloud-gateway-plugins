# gateway-filters

Exercises [spring-cloud-gateway-filters](../../../spring-cloud-gateway-filters/README.md) on
its own, on port `8201`.

## Running it

```console
mvn spring-boot:run
```

The `Authorization` routes forward to the `service-a` sample on `:8080`; start it too to see
a granted call reach a backend. The other routes need nothing beyond an internet connection.

## What it shows

| Url | Filter | What happens |
| --- | --- | --- |
| http://localhost:8201/authorization/sample | `Authorization` | `user:user` holds `ROLE_READ` and is let through; `admin:admin` is answered `403` |
| http://localhost:8201/authorization-ko/sample | `Authorization` | the route asks for an authority no account holds, so every request is answered `403` |
| http://localhost:8201/convert-method/anything | `ConvertHttpMethod` | a `GET` reaches httpbin.org as a `POST` |
| any response | `CorrelationId` | an `x-correlation-id` header carrying the traceId of the exchange |

```console
$ curl -u user:user http://localhost:8201/authorization/sample -i
HTTP/1.1 200 OK
x-correlation-id: 68a2f0c1d5b34e9a...

$ curl -u admin:admin http://localhost:8201/authorization/sample
{"status":403,"error":"Forbidden", ...}

$ curl http://localhost:8201/convert-method/anything | jq .method
"POST"
```

## The authority prefix

`Authorization` compares the configured values against `GrantedAuthority.getAuthority()`,
**as they are**. An account built with `roles("READ")` carries the authority `ROLE_READ`, so
the route asks for `ROLE_READ` and not `READ` — the prefix is part of the value being
compared. Where the authorities come from a JWT instead, they carry whatever the token
declared, with no prefix added; see the [gateway-secured](../gateway-secured/README.md)
sample, whose route asks for a bare `READ`.

## Recaptcha

The fourth filter of the plugin needs a secret key issued by Google, so the sample declares
the route commented out in [`application.yml`](src/main/resources/application.yml) rather
than shipping one that cannot answer.

Before enabling it, note that the filter denies with `403` on **every** outcome that is
not a verified token — a missing token, a rejected one, a score below the threshold, and a
verification endpoint that is unreachable or answers an error. It fails closed, and the
reason goes to the log rather than to the caller.
