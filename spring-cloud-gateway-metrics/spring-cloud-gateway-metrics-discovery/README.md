# spring-cloud-gateway-metrics-discovery

Consolidates the traffic figures by asking every instance registered under the gateway's
own service id what it counted, and summing the answers.

No infrastructure beyond the service registry already in place — Eureka, Kubernetes, or any
other `ReactiveDiscoveryClient`.

## Dependency

```xml
    <dependency>
       <groupId>ch.nexsol-tech.gateway</groupId>
       <artifactId>spring-cloud-gateway-metrics-discovery</artifactId>
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
            provider: discovery
            discovery:
              service-id: gateway        # defaults to spring.application.name
              path: /ui/metrics/local
              timeout: 3s
```

| Property | Default | Description |
|----------|---------|-------------|
| `service-id` | `spring.application.name` | Id this gateway is registered under |
| `path` | `/ui/metrics/local` | Path the sibling instances are polled on |
| `timeout` | `3s` | How long to wait for a sibling before leaving it out |

## The endpoint the siblings poll

This module registers `GET /ui/metrics/local`, which answers with **this instance's own**
meter registry — never with the consolidated figures.

That distinction is the whole design. Were the fan-out to poll the consolidated endpoint,
every instance would poll every other one, which would poll it back, forever. The local
endpoint is the base case that terminates the recursion.

**The path must be reachable between instances.** This module does not depend on the UI, so
it cannot declare the path to the shell's security chain: an application that secures
everything must permit it itself, or the siblings answer 401 and each instance ends up
reporting only its own traffic.

## What it costs

- One HTTP call per instance, on every refresh — the traffic view repolls every 5 seconds.
- Counters live in memory, so an instance that restarts loses what it had counted. Only the
  Prometheus source keeps history across restarts.

An instance that fails or times out is left out rather than failing the whole reading, and
the view says so: `2 of 3 instances (the others did not answer)`.
