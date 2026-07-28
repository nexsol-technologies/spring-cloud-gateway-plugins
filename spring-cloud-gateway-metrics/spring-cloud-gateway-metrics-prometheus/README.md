# spring-cloud-gateway-metrics-prometheus

Reads the traffic figures from Prometheus instead of the meter registry of the instance
that answered, so the traffic view shows the whole gateway.

This is the only source that survives an instance being replaced: the series outlive the
JVM that produced them, so a rolling restart does not reset the figures.

## Dependency

```xml
    <dependency>
       <groupId>ch.nexsol-tech.gateway</groupId>
       <artifactId>spring-cloud-gateway-metrics-prometheus</artifactId>
       <version>${spring-cloud-gateway-plugins.version}</version>
    </dependency>
```

## Configuration

```yaml
spring:
  cloud:
    gateway:
      server:
        webflux:
          metrics:
            provider: prometheus
            prometheus:
              url: http://prometheus:9090
              selector: job="gateway",namespace="prod"   # see below
              meter: spring_cloud_gateway_requests_seconds
              timeout: 5s
```

| Property | Default | Description |
|----------|---------|-------------|
| `url` | _(unset)_ | Base URL of the Prometheus server |
| `selector` | _(empty)_ | Extra label matchers, without the braces |
| `meter` | `spring_cloud_gateway_requests_seconds` | Base name of the gateway request timer |
| `timeout` | `5s` | How long to wait before reporting no data |
| `username` / `password` | _(unset)_ | Basic credentials, when the server asks for them |
| `token` | _(unset)_ | Bearer token, ignored when Basic credentials are set |

## Authentication

Optional: a Prometheus that needs no authentication works with nothing configured, and no
`Authorization` header is sent.

```yaml
metrics:
  prometheus:
    username: gateway              # Basic, e.g. behind nginx or on Grafana Cloud
    password: ${PROM_PASSWORD}
    # or
    token: ${PROM_TOKEN}           # Bearer, e.g. Thanos, Mimir, OpenShift monitoring
```

The credentials go on a client dedicated to Prometheus, not through a `WebClientCustomizer`
— which would put them on every client of the application.

For anything properties cannot express — mTLS, OAuth2 client credentials, a Kubernetes
service account token that rotates — declare the client yourself and it is used instead:

```java
@Bean
WebClient prometheusMetricsWebClient(WebClient.Builder builder) {
    return builder.baseUrl("https://prometheus:9090")
        .filter(myAuthenticationFilter())
        .build();
}
```

A `token` read from configuration is read once, at startup, which is why a rotating one
needs that bean rather than the property.

When Prometheus refuses the credentials the view says so — `authentication refused (401)`
rather than `unreachable`, so an expired secret does not look like a network outage.

**Set `selector` unless this gateway is the only publisher of the meter.** A shared
Prometheus otherwise mixes the traffic of every gateway into one figure — wrong in a way
nothing on the page would reveal.

## How it works

Three instant queries are issued concurrently, since Prometheus answers one expression per
call:

```promql
sum by (routeId, routeUri, httpStatusCode) (spring_cloud_gateway_requests_seconds_count{...})
sum by (routeId, routeUri, httpStatusCode) (spring_cloud_gateway_requests_seconds_sum{...})
max by (routeId)                           (spring_cloud_gateway_requests_seconds_max{...})
```

The count and the total stay split per status so the 4xx and 5xx shares are counted without
a fourth round trip. Durations are converted from seconds to milliseconds.

The gateway must publish its metrics to Prometheus for any of this to exist — add
`micrometer-registry-prometheus` and let Prometheus scrape `/actuator/prometheus`.

## When Prometheus cannot be reached

The view reports no data and says why, rather than failing the page — a read-only view is
not worth an error page:

| Coverage shown | Meaning |
|----------------|---------|
| `… — unreachable` | The server never answered: wrong host, network, timeout |
| `… — authentication refused (401)` | The credentials were rejected or missing |
| `… — refused with 500` | The server answered, with an error of its own |
