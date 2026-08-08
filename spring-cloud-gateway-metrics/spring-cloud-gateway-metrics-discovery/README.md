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
              instance-path: /ui/metrics/local/instance
              timeout: 3s
```

| Property | Default | Description |
|----------|---------|-------------|
| `service-id` | `spring.application.name` | Id this gateway is registered under |
| `path` | `/ui/metrics/local` | Path the siblings are polled on for their route figures |
| `instance-path` | `/ui/metrics/local/instance` | Path the siblings are polled on for their technical figures |
| `timeout` | `3s` | How long to wait for a sibling before leaving it out |

## The best fit for the instance figures

This is the provider that suits the per-instance figures best, which is the reverse of how
it ranks for the route figures. A per-instance reading is native to the instance: the
registry hands out exactly one row per live instance, in real time, with no identity to
reconstruct and nothing to merge. An instance that stops is gone from the next refresh
rather than lingering until a key or a series expires.

The rows are concatenated, never summed — a gateway with the combined heap of three
instances is not a thing that exists. The address each row is stamped with comes from the
registry rather than from the instance, which cannot know where it is reachable from.

## The registry

This module declares no registry property of its own: it reuses the
`ReactiveDiscoveryClient` your application already has, so the registry is configured the
usual way. **The reactive client is the one that matters** — a blocking `DiscoveryClient`
alone is not enough, though both Eureka and Kubernetes provide the reactive one in a WebFlux
application.

What the module needs from it is one entry **per instance**, each carrying a URI it can be
reached on.

### Eureka

```xml
    <dependency>
       <groupId>org.springframework.cloud</groupId>
       <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
```

```yaml
spring:
  application:
    name: gateway                          # the id the instances register under
eureka:
  client:
    service-url:
      defaultZone: http://eureka:8761/eureka/
  instance:
    prefer-ip-address: true                # only where the registered host name does not resolve
```

`spring.application.name` is both what Eureka registers and what this module looks up, so
nothing else is needed — leave `service-id` unset.

The instances are polled on the address Eureka holds for them, so that address has to
resolve **from the other instances**. Where the network already provides DNS for it — Docker
Swarm and Compose resolve service and container names on their own — the registered host
name works as is and `prefer-ip-address` is unnecessary. Turn it on where the host name
resolves nowhere, a Kubernetes pod name being the usual case: otherwise every sibling times
out and the view falls back to `0 of 3 instances`.

### Kubernetes

```xml
    <dependency>
       <groupId>org.springframework.cloud</groupId>
       <artifactId>spring-cloud-starter-kubernetes-client</artifactId>
    </dependency>
```

```yaml
spring:
  cloud:
    kubernetes:
      discovery:
        enabled: true
        primary-port-name: http            # when the Service exposes more than one port
    gateway:
      server:
        webflux:
          metrics:
            provider: discovery
            discovery:
              service-id: gateway          # the Kubernetes Service name
```

Three things differ from Eureka:

- **The id is the Service name**, not `spring.application.name`. Set `service-id` explicitly
  unless the two happen to match.
- **The instances come from the Service endpoints**, one per *ready* pod. A pod still
  starting up is not polled — it is simply left out, and the coverage says so.
- **The pod needs RBAC** to read them. Without `get`/`list`/`watch` on `services` and
  `endpoints`, discovery returns nothing and the view reports
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

Note that the pods are polled directly, one by one, and never through the Service
ClusterIP — which would load-balance every poll onto a random pod and count the same one
several times.

## The endpoints the siblings poll

This module registers `GET /ui/metrics/local` and `GET /ui/metrics/local/instance`, which
answer with **this instance's own** meter registry — never with the consolidated figures.

That distinction is the whole design. Were the fan-out to poll the consolidated endpoint,
every instance would poll every other one, which would poll it back, forever. The local
endpoint is the base case that terminates the recursion.

**The paths must be reachable between instances.** This module does not depend on the UI, so
it cannot declare them to the shell's security chain: an application that secures
everything must permit them itself, or the siblings answer 401 and each instance ends up
reporting only its own traffic.

## What it costs

- One HTTP call per instance and per view, on every refresh — both views repoll every
  5 seconds.
- Counters live in memory, so an instance that restarts loses what it had counted. Only the
  Prometheus source keeps history across restarts.

An instance that fails or times out is left out rather than failing the whole reading, and
the view says so: `2 of 3 instances (the others did not answer)`.
