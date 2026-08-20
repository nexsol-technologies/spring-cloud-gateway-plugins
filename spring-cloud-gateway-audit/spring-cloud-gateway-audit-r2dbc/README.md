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

spring.r2dbc:
  url: r2dbc:postgresql://localhost:5432/gateway
  username: gateway
  password: ${DB_PASSWORD}
```

| Property | Default | What it does |
| --- | --- | --- |
| `...audit.r2dbc.table` | `audit_event` | Destination table |

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
