# spring-cloud-gateway-audit-r2dbc

R2DBC provider for the [auditing plugin](../README.md). Inserts each audit event into a
relational table, storing the timestamp and the attributes rendered as a JSON string.

## Dependency

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-audit-r2dbc</artifactId>
</dependency>
```

It brings `spring-boot-starter-r2dbc`, so Spring Boot auto-configures a `DatabaseClient`
from your `spring.r2dbc.*` properties and the provider reuses it. Add the R2DBC driver of
your database (for example `org.postgresql:r2dbc-postgresql`).

## Configuration

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          audit:
            provider: r2dbc
            r2dbc:
              table: audit_event       # default
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/gateway
    username: gateway
    password: ${DB_PASSWORD}
```

| Property | Default | Description |
|----------|---------|-------------|
| `spring.cloud.gateway.server.webflux.audit.r2dbc.table` | `audit_event` | Destination table |

## Schema

The table must expose an `event_timestamp` column (written as a UTC `LocalDateTime`) and an
`attributes` column (the JSON string). The insert issued is:

```sql
INSERT INTO <table> (event_timestamp, attributes) VALUES (:eventTimestamp, :attributes)
```

Example schema (PostgreSQL):

```sql
CREATE TABLE audit_event (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_timestamp TIMESTAMP NOT NULL,
    attributes      TEXT NOT NULL
);
```

Use `jsonb` for `attributes` on PostgreSQL if you want to query inside the payload. The
provider does not create or migrate the schema; manage it with your usual migration tool
(Flyway, Liquibase, ...).

## Payload

The `attributes` column stores the JSON object of the event attributes, for example:

```json
{"request.method":"GET","request.path":"/patient/99098875/alert-summaries","response.status":"OK","jwt.user.id":"toto"}
```
