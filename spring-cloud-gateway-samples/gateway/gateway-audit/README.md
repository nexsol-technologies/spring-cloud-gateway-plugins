# gateway-audit

Exercises [spring-cloud-gateway-audit](../../../spring-cloud-gateway-audit/README.md) on port
`8205`, with its three providers behind a profile each.

## Running it

```console
mvn spring-boot:run
```

The default profile needs no infrastructure: the core default publisher logs each event and
republishes it as a Spring application event, which
[`AuditEventListener`](src/main/java/ch/nexsol/gateway/sample/audit/AuditEventListener.java)
picks up and logs. Call an audited route and watch it:

```console
$ curl http://localhost:8205/audited-httpbin/get
```

```
INFO  c.n.g.s.audit.AuditEventListener : audit {request.method=GET, request.path=/audited-httpbin/get,
  response.status=OK, route.id=audited_httpbin, route.metadata.tenant=globex,
  route.metadata.criticality=low, metadata.environment=sample, metadata.datacenter=geneva,
  trace.id=..., span.id=..., jwt.user.id=_none_}
```

## Where auditing runs

| Url | Audited |
| --- | --- |
| http://localhost:8205/audited/sample | yes — the route carries the `Audit` filter (backend: `service-a` on `:8080`, audited even when nothing answers there) |
| http://localhost:8205/audited-httpbin/get | yes — the route carries the `Audit` filter |
| http://localhost:8205/not-audited/get | no — same backend, no filter |

The `global` profile swaps the per-route filter for the web filter and audits **everything**
the gateway answers, the actuator endpoints included:

```console
mvn spring-boot:run -Dspring-boot.run.profiles=global
```

The console is the exception: it excludes the paths it serves itself, so browsing
http://localhost:8205/ui does not fill the trail with the pages, fragments and assets of the
console. Exclude more with
`spring.cloud.gateway.server.webflux.audit.web-filter.exclude-paths`.

## What travels with an event

Two distinct namespaces, which is why a route metadata and a gateway metadata may share a
name without either overwriting the other:

- `route.metadata.*` — declared on the route that actually matched. `tenant`, `criticality`,
  the owning team: whatever the configuration says about *this* route.
- `metadata.*` — declared once, on the gateway, and stamped on every event.
  `environment=sample`, `datacenter=geneva` here.

An exchange no route handled is audited as `route.id=_none_`, with no metadata attribute.

## The providers

Each is selected with `audit.provider`, and each backs a profile. Start only the container
the profile needs.

### Redis

```console
docker compose up -d redis
mvn spring-boot:run -Dspring-boot.run.profiles=redis
redis-cli SUBSCRIBE gateway-audit
```

Genuine pub/sub: events published without a subscriber are lost. Subscribe **before**
calling a route, or nothing shows up.

### Kafka

```console
docker compose up -d kafka
mvn spring-boot:run -Dspring-boot.run.profiles=kafka
docker exec gateway-audit-kafka \
  /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic gateway-audit --from-beginning
```

### R2DBC

```console
docker compose up -d postgres
mvn spring-boot:run -Dspring-boot.run.profiles=r2dbc
docker exec -it gateway-audit-postgres \
  psql -U gateway -d gateway_audit -c 'SELECT event_timestamp, attributes FROM audit_event'
```

The provider does not create the schema — [`schema.sql`](src/main/resources/schema.sql) does
it here, and a real deployment would use Flyway or Liquibase.

Combine a provider with the global web filter by activating both profiles:
`-Dspring-boot.run.profiles=global,redis`.

## The audit view

`spring-cloud-gateway-ui` is on the classpath, so http://localhost:8205/ui/audit shows a live
tail of the events and the home page counts them.

## Writing your own publisher

Two ways, both shown by the plugin rather than by this sample:

- listen to `AuditApplicationEvent`, as `AuditEventListener` does — the default publisher
  keeps running and you forward what you want;
- declare an `AuditEventPublisher` bean, and the default publisher and every provider back
  off. It must not block the event loop.
