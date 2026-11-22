# spring-cloud-gateway-audit-r2dbc

R2DBC provider for the [auditing plugin](../README.md). Inserts each audit event into a
relational table: the timestamp, and the attributes rendered as a JSON string.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-audit-r2dbc</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

It brings `spring-boot-starter-r2dbc`, so Spring Boot auto-configures a `DatabaseClient` from
the `spring.r2dbc.*` properties and the provider reuses it. Add the R2DBC driver of your
database (for example `org.postgresql:r2dbc-postgresql`).

## Configuration

```yaml
spring.cloud.gateway.server.webflux.audit:
  provider: r2dbc
  r2dbc:
    table: audit_event
    max-pending: 10000           # inserts held while the database does not keep up; past it they are dropped
    send-timeout: 30s            # how long one insert may take

spring.r2dbc:
  url: r2dbc:postgresql://localhost:5432/gateway
  username: gateway
  password: ${DB_PASSWORD}
```

| Property | Default | What it does |
| --- | --- | --- |
| `...audit.r2dbc.table` | `audit_event` | Destination table |
| `...audit.r2dbc.max-pending` | `10000` | How many inserts may be pending at once; past it an event is dropped and reported. `0` sets no limit |
| `...audit.r2dbc.send-timeout` | `30s` | How long one insert may take before it is given up and counted as failed. `0` sets no timeout |

## When the database does not keep up

Publishing never waits, so a database slower than the traffic does not slow a request down:
the events pile up instead, each holding its payload. `max-pending` bounds them. Past it an
event is **dropped**, and the gateway says so in the log — one `WARN` at most every 30
seconds, carrying how many events were dropped and how many the database did not take since
startup:

```
audit events are being lost towards the table audit_event: 124 dropped over the limit of 10000 pending, 3 failed (totals since startup, reported at most every 30 s)
```

Each event is one `INSERT`, waiting its turn for a connection of the pool — which has no
acquisition timeout unless `spring.r2dbc.pool.max-acquire-time` sets one. `send-timeout` gives
the place of an insert back when the database never answers, so the events that follow are
not dropped for good.

## Schema

The table must expose an `event_timestamp` column (written as a UTC `LocalDateTime`) and an
`attributes` column (the JSON string). The insert issued is:

```sql
INSERT INTO <table> (event_timestamp, attributes) VALUES (:eventTimestamp, :attributes)
```

PostgreSQL example — use `jsonb` for `attributes` to query inside the payload:

```sql
CREATE TABLE audit_event (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_timestamp TIMESTAMP NOT NULL,
    attributes      TEXT NOT NULL
);
```

The provider does not create or migrate the schema; manage it with your usual migration tool
(Flyway, Liquibase, …).

## Payload

The `attributes` column stores the JSON object of the event attributes:

```json
{"request.method":"GET","request.path":"/book/99098875/reviews","response.status":"OK","jwt.user.id":"toto"}
```

## Sample

[gateway-audit](../../spring-cloud-gateway-samples/gateway/gateway-audit/README.md),
`r2dbc` profile — port `8205`.
