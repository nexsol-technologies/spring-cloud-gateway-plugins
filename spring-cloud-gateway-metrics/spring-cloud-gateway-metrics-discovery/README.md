# spring-cloud-gateway-metrics-discovery

Consolidates the figures by asking every instance registered under the gateway's own service
id what it counted, and summing the answers.

No infrastructure beyond the service registry already in place — Eureka, Kubernetes, or any
other `ReactiveDiscoveryClient`. This is the best fit for the **instance** figures: a
per-instance reading is native to its instance, the registry hands out exactly one row per
live instance, and an instance that stops is gone from the next refresh rather than lingering
until a key or a series expires.

## Install

```xml
<dependency>
    <groupId>ch.nexsol-tech.gateway</groupId>
    <artifactId>spring-cloud-gateway-metrics-discovery</artifactId>
    <version>${spring-cloud-gateway-plugins.version}</version>
</dependency>
```

The module declares no registry property of its own: it reuses the `ReactiveDiscoveryClient`
the application already has. **The reactive client is the one that matters** — a blocking
`DiscoveryClient` alone is not enough, though Eureka and Kubernetes both provide the reactive
one in a WebFlux application.

## Configuration

All properties are under `spring.cloud.gateway.server.webflux.metrics`.

```yaml
spring.cloud.gateway.server.webflux.metrics:
  provider: discovery
  discovery:
    service-id: gateway        # defaults to spring.application.name
    path: /ui/metrics/local
    instance-path: /ui/metrics/local/instance
    timeout: 3s
```

| Property | Default | What it does |
| --- | --- | --- |
| `...discovery.service-id` | `spring.application.name` | Id this gateway is registered under |
| `...discovery.path` | `/ui/metrics/local` | Path the siblings are polled on for their route figures |
| `...discovery.instance-path` | `/ui/metrics/local/instance` | Path the siblings are polled on for their instance figures |
| `...discovery.timeout` | `3s` | How long to wait for a sibling before leaving it out |

## The endpoints the siblings poll

This module registers `GET /ui/metrics/local` and `GET /ui/metrics/local/instance`, which
answer with **this instance's own** meter registry — never with the consolidated figures.
That is the base case terminating the recursion: were the fan-out to poll the consolidated
endpoint, every instance would poll every other one, forever.

**The paths must be reachable between instances.** The module declares them, as configured,
through a `SecuredPaths.open(...)` bean from
[`spring-cloud-gateway-commons`](../../spring-cloud-gateway-commons/README.md), so a gateway
carrying the [console](../../spring-cloud-gateway-ui/README.md) has them permitted whether
that console is open or behind a login — the fan-out polls with no credentials, and a login
page it cannot answer would leave each instance reporting only its own traffic.

That is a deliberate exposure: route names, request counts and JVM figures, read-only. An
application without the console has to permit them itself; one that would rather close them
declares its own `discoveryMetricsSecuredPaths` bean, and gives up the consolidation.

## Eureka

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
```

```yaml
spring.application.name: gateway         # the id the instances register under
eureka:
  client.service-url.defaultZone: http://eureka:8761/eureka/
  instance.prefer-ip-address: true       # only where the registered host name does not resolve
```

`spring.application.name` is both what Eureka registers and what this module looks up, so
leave `service-id` unset.

The instances are polled on the address Eureka holds for them, so that address has to resolve
**from the other instances**. Docker Swarm and Compose resolve service and container names on
their own, so the registered host name works as is; turn `prefer-ip-address` on where it
resolves nowhere — a Kubernetes pod name being the usual case — or every sibling times out
and the view falls back to `0 of 3 instances`.

## Kubernetes

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-kubernetes-client</artifactId>
</dependency>
```

```yaml
spring.cloud.kubernetes.discovery:
  enabled: true
  primary-port-name: http    # when the Service exposes more than one port
spring.cloud.gateway.server.webflux.metrics:
  provider: discovery
  discovery.service-id: gateway   # the Kubernetes Service name, not spring.application.name
```

Three things differ from Eureka:

* **The id is the Service name.** Set `service-id` explicitly unless the two happen to match.
* **The instances come from the Service endpoints**, one per *ready* pod. A pod still starting
  is left out, and the coverage says so.
* **The pod needs RBAC** to read them, or discovery returns nothing and the view reports
  `no instance registered as 'gateway'`:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: gateway-discovery
rules:
  - apiGroups: [""]
    resources: ["services", "endpoints"]
    verbs: ["get", "list", "watch"]
```

The pods are polled directly, one by one, never through the Service ClusterIP — which would
load-balance every poll onto a random pod and count the same one several times.

## What it costs

* One HTTP call per instance and per view, on every refresh — both views repoll every 5s.
* Counters live in memory, so an instance that restarts loses what it had counted. Only the
  [Prometheus source](../spring-cloud-gateway-metrics-prometheus/README.md) keeps history.

An instance that fails or times out is left out rather than failing the whole reading, and the
view says so: `2 of 3 instances (the others did not answer)`.

## Sample

[gateway-metrics](../../spring-cloud-gateway-samples/gateway/gateway-metrics/README.md),
`discovery` profile — port `8206`, with `eureka` on `8761`.
