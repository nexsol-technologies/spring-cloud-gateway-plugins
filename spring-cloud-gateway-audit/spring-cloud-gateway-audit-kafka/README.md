# spring-cloud-gateway-audit-kafka

Kafka provider for the [auditing plugin](../README.md). Publishes each audit event,
rendered as a JSON object of its attributes, to a Kafka topic.

## Dependency

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-audit-kafka</artifactId>
</dependency>
```

It brings `spring-boot-starter-kafka`, so Spring Boot auto-configures a `KafkaTemplate`
from your `spring.kafka.*` properties and the provider reuses it. No connection setup is
duplicated by this module.

## Configuration

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          audit:
            provider: kafka
            kafka:
              topic: gateway-audit     # default
  kafka:
    bootstrap-servers: broker:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
```

| Property | Default | Description |
|----------|---------|-------------|
| `spring.cloud.gateway.server.webflux.audit.kafka.topic` | `gateway-audit` | Destination topic |

The event is sent as a String value with a `null` key, so set the key and value
serializers to `StringSerializer` as shown above.

## Authentication (SASL / SSL)

Authentication is handled entirely by the auto-configured Kafka producer, so it is
configured with the standard `spring.kafka.*` keys - this module needs nothing extra. Set
`security.protocol` and, for SASL, `sasl.mechanism` and `sasl.jaas.config`. The four common
setups:

### SASL_SSL + PLAIN (e.g. Confluent Cloud, managed brokers)

```yaml
spring:
  kafka:
    bootstrap-servers: pkc-xxxxx.europe-west1.gcp.confluent.cloud:9092
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: PLAIN
      sasl.jaas.config: >-
        org.apache.kafka.common.security.plain.PlainLoginModule required
        username="${KAFKA_API_KEY}"
        password="${KAFKA_API_SECRET}";
```

### SASL_SSL + SCRAM (SCRAM-SHA-256 / SCRAM-SHA-512)

```yaml
spring:
  kafka:
    bootstrap-servers: broker:9093
    properties:
      security.protocol: SASL_SSL
      sasl.mechanism: SCRAM-SHA-512
      sasl.jaas.config: >-
        org.apache.kafka.common.security.scram.ScramLoginModule required
        username="${KAFKA_USER}"
        password="${KAFKA_PASSWORD}";
```

### SASL_PLAINTEXT + SCRAM (SASL without TLS, e.g. inside a trusted network)

```yaml
spring:
  kafka:
    bootstrap-servers: broker:9092
    properties:
      security.protocol: SASL_PLAINTEXT
      sasl.mechanism: SCRAM-SHA-512
      sasl.jaas.config: >-
        org.apache.kafka.common.security.scram.ScramLoginModule required
        username="${KAFKA_USER}"
        password="${KAFKA_PASSWORD}";
```

### SSL / mTLS (client certificate)

```yaml
spring:
  kafka:
    bootstrap-servers: broker:9093
    security:
      protocol: SSL
    ssl:
      trust-store-location: file:/etc/kafka/client.truststore.jks
      trust-store-password: ${KAFKA_TRUSTSTORE_PASSWORD}
      key-store-location: file:/etc/kafka/client.keystore.jks
      key-store-password: ${KAFKA_KEYSTORE_PASSWORD}
      key-password: ${KAFKA_KEY_PASSWORD}
```

GSSAPI/Kerberos and OAUTHBEARER work the same way: set `sasl.mechanism` accordingly and
provide the matching `sasl.jaas.config`.

> Keep credentials out of the file: reference environment variables or an external secret
> store (as with `${KAFKA_API_KEY}` above) rather than hardcoding them.

## Payload

The message value is the JSON object of the event attributes, for example:

```json
{"request.method":"GET","request.path":"/patient/99098875/alert-summaries","response.status":"OK","jwt.user.id":"toto"}
```
