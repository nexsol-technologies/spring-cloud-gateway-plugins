# gateway-filters

Exercises [spring-cloud-gateway-filters](../../../spring-cloud-gateway-filters/README.md) on
its own — port `8201`.

## Run it

```console
mvn spring-boot:run
```

The `Authorization` routes forward to the `service-a` sample on `:8080`, and so do the three
maintenance routes that let a caller through — `maintenance-admin` for `admin:admin`,
`maintenance-planned`, whose window is still ahead, and `maintenance-notice`, which only
announces one. Start `service-a` too to see a granted
call reach a backend. The routes that are actually closed answer from the gateway itself and
need nothing; the rest need nothing beyond an internet connection.

## What to look at

| Url | Filter | What happens |
| --- | --- | --- |
| http://localhost:8201/authorization/sample | `Authorization` | `user:user` holds `ROLE_READ` and is let through; `admin:admin` is answered `403` |
| http://localhost:8201/authorization-ko/sample | `Authorization` | The route asks for an authority no account holds, so every request is answered `403` |
| http://localhost:8201/maintenance/sample | `Maintenance` | The route is closed to everyone, answered `593` with the message and the window |
| http://localhost:8201/maintenance-admin/sample | `Maintenance` | The same maintenance, lifted for `admin:admin`; everyone else is answered `593` |
| http://localhost:8201/maintenance-bounded/sample | `Maintenance` | A window with a known end, so the answer carries a `Retry-After` |
| http://localhost:8201/maintenance-planned/sample | `Maintenance` | The window opens in 2125, so the route is served normally |
| http://localhost:8201/maintenance-notice/sample | `Maintenance` | Pre-maintenance: answered `200` by `service-a`, with the window announced in the headers |
| http://localhost:8201/convert-method/anything | `ConvertHttpMethod` | A `GET` reaches httpbin.org as a `POST` |
| any response | `CorrelationId` | An `x-correlation-id` header carrying the traceId of the exchange |

```console
$ curl -u user:user http://localhost:8201/authorization/sample -i
HTTP/1.1 200 OK
x-correlation-id: 68a2f0c1d5b34e9a...

$ curl -u admin:admin http://localhost:8201/authorization/sample
{"status":403,"error":"Forbidden", ...}

$ curl http://localhost:8201/convert-method/anything | jq .method
"POST"

$ curl http://localhost:8201/maintenance/sample -i
HTTP/1.1 593 Server Error (593)
content-type: application/json
{"message":"The shop is closed until 4am.","start":null,"end":null}

$ curl http://localhost:8201/maintenance-bounded/sample -i
HTTP/1.1 593 Server Error (593)
retry-after: Sun, 02 Sep 2125 02:00:00 GMT

$ curl -u admin:admin http://localhost:8201/maintenance-admin/sample -i
HTTP/1.1 200 OK

$ curl http://localhost:8201/maintenance-notice/sample -i
HTTP/1.1 200 OK
Sunset: Sun, 01 Sep 2126 22:00:00 GMT
x-maintenance-start: 2126-09-01T22:00:00Z
x-maintenance-end: 2126-09-02T02:00:00Z
x-maintenance-message: Maintenance%20pr%C3%A9vue%20le%201er%20septembre.
```

## The authority prefix

`Authorization` compares the configured values against `GrantedAuthority.getAuthority()`, **as
they are**. An account built with `roles("READ")` carries the authority `ROLE_READ`, so the
route asks for `ROLE_READ` and not `READ` — the prefix is part of the value being compared.
Where the authorities come from a JWT instead, they carry whatever the token declared, with no
prefix added; see [gateway-secured](../gateway-secured/README.md), whose route asks for a bare
`READ`.

## Maintenance

`593` is not a status HTTP defines, which is the point: it separates a planned outage from the
`503` an overloaded or unreachable backend produces. Netty sends it as it is configured and
names it `Server Error (593)`, having no phrase of its own for it — the body is what a front end
reads.

The `maintenance-notice` route shows the other half: while the notice runs, nothing about the
answer changes — `service-a` replies as it always does — and the window travels in the headers
beside it. The message is percent-encoded UTF-8, since a header value is US-ASCII and the accent
in `prévue` would not survive otherwise; `decodeURIComponent` reads it back.

The bypass needs an authenticated caller, so it only means something behind a filter chain that
populates one. Here Basic authentication runs on every path, which is why `-u admin:admin` is
enough; a gateway authenticating with a JWT exempts on claims instead, through `allowed-claims`.

## Recaptcha

The fifth filter needs a secret key issued by Google. Its route sits commented out in
[`application.yml`](src/main/resources/application.yml): uncomment it and fill in your own key.

Before enabling it, note that the filter denies with `403` on **every** outcome that is not a
verified token — a missing token, a rejected one, a score below the threshold, an unreachable
verification endpoint. It fails closed, and the reason goes to the log rather than to the
caller.
