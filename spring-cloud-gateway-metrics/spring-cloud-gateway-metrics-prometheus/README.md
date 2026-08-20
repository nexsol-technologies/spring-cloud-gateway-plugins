# spring-cloud-gateway-metrics-prometheus

Reads both families of figures from Prometheus instead of from the meter registry of the
instance that answered, so the views show the whole gateway.

The only source that survives an instance being replaced: the series outlive the JVM that
produced them, so a rolling restart does not reset the figures.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-metrics-prometheus</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

The gateway must publish to Prometheus for any of this to exist: add
`micrometer-registry-prometheus` and let Prometheus scrape `/actuator/prometheus`.

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.metrics`.

```yaml
spring.cloud.gateway.server.webflux.metrics:
  provider: prometheus
  prometheus:
    url: http://prometheus:9090
    # Extra label matchers, without the braces. Set this unless
    # this gateway is the only publisher of the meter.
    selector: job="gateway",namespace="prod"
    # Label naming an instance. The default is the scrape target
    # (host:port), so point it at a pod label when there is one.
    instance-label: instance
    # How far back an instance must have reported to still be listed.
    stale-after: 2m
    timeout: 5s
```

| Property | Default | What it does |
| --- | --- | --- |
| `...prometheus.url` | — | Base URL of the Prometheus server |
| `...prometheus.selector` | — | Extra label matchers, written without the braces |
| `...prometheus.meter` | `spring_cloud_gateway_requests_seconds` | Base name of the gateway request timer |
| `...prometheus.instance-label` | `instance` | Label identifying a gateway instance |
| `...prometheus.stale-after` | `2m` | How far back an instance must have reported to still be listed |
| `...prometheus.timeout` | `5s` | How long to wait before reporting no data |
| `...prometheus.username` / `.password` | — | Basic credentials, when the server asks for them |
| `...prometheus.token` | — | Bearer token, ignored when Basic credentials are set |

> **Set `selector`** unless this gateway is the only publisher of the meter. A shared
> Prometheus otherwise mixes the traffic of every gateway into one figure — wrong in a way
> nothing on the page would reveal.

## Authentication

Optional: a Prometheus needing none works with nothing configured, and no `Authorization`
header is sent. The credentials go on a client dedicated to Prometheus, never through a
`WebClientCustomizer` — which would put them on every client of the application.

For anything properties cannot express — mTLS, OAuth2 client credentials, a rotating
Kubernetes service account token — declare the client yourself and it is used instead:

```java
@Bean
WebClient prometheusMetricsWebClient(WebClient.Builder builder) {
    return builder.baseUrl("https://prometheus:9090")
        .filter(myAuthenticationFilter())
        .build();
}
```

A `token` read from configuration is read once at startup, which is why a rotating one needs
that bean rather than the property.

## How it works

**Route figures — three instant queries**, issued concurrently since Prometheus answers one
expression per call. The count and total stay split per status so the 4xx and 5xx shares
cost no fourth round trip; durations are converted from seconds to milliseconds.

```promql
sum by (routeId, routeUri, httpStatusCode) (spring_cloud_gateway_requests_seconds_count{...})
sum by (routeId, routeUri, httpStatusCode) (spring_cloud_gateway_requests_seconds_sum{...})
max by (routeId)                           (spring_cloud_gateway_requests_seconds_max{...})
```

**Instance figures — one query, not one per counter.** The twenty-odd JVM, system and
Reactor Netty series are selected by name in a single expression and grouped per instance
here:

```promql
last_over_time({__name__=~"jvm_memory_used_bytes|process_cpu_usage|…",job="gateway"}[120s])
```

Three consequences, all the reverse of what makes this the best route source:

* **Instances that no longer run.** Prometheus keeps their series — the very property that
  lets route figures survive a restart. `last_over_time(…[stale-after])` is what drops an
  instance that stopped reporting.
* **How rows are named.** The default `instance` label is the scrape target, a host and port,
  not the `instance-id` the plugin resolves. Set `instance-label` when the deployment
  publishes a pod or application instance name.
* **Only the historical Micrometer names are selected.** An instance whose JVM binders use
  the OpenTelemetry conventions reports its memory and processor figures under other names,
  and those two come back empty here — unlike the local source, which reads both.

Prometheus cannot know how a remote instance was configured, so the pool and event loop
counters are reported as collected when their series exist and as off when they do not.

## When Prometheus cannot be reached

The views report no data and say why, rather than failing the page:

| Coverage shown | Meaning |
| --- | --- |
| `… — unreachable` | The server never answered: wrong host, network, timeout |
| `… — authentication refused (401)` | The credentials were rejected or missing |
| `… — refused with 500` | The server answered, with an error of its own |

## Sample

[gateway-metrics](../../spring-cloud-gateway-samples/gateway/gateway-metrics/README.md),
`prometheus` profile — port `8206`, Prometheus on `9091`.
