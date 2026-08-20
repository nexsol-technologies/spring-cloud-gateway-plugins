# gateway-metrics

Exercises [spring-cloud-gateway-metrics](../../../spring-cloud-gateway-metrics/README.md) —
port `8206`, with its three consolidating sources behind a profile each.

## Run it

```console
mvn spring-boot:run
```

Generate some traffic, then open http://localhost:8206/ui/metrics:

```console
curl http://localhost:8206/traffic/get
curl http://localhost:8206/errors/status/500
curl http://localhost:8206/errors/status/404
```

## What to look at

http://localhost:8206/ui/metrics answers *which route carries the load*.
http://localhost:8206/ui/metrics/instances answers *which instance is in trouble*: heap,
processor, threads, and the connection pools towards the downstream services.

This sample turns both instrumentation switches on, which is what makes the pool and event loop
sections appear at all:

```yaml
spring.cloud.gateway.server.webflux.httpclient.pool.metrics: true
spring.cloud.gateway.server.webflux.metrics.instance.instrument-http-client: true
```

Neither is on by default: they add a metrics recorder to the pipeline of every connection, so
they cost something on the data path. Turn them off here and the view keeps working — it
reports the counters as disabled and names the property to set, per instance, rather than
showing an empty table. The pool rows only appear once a downstream has actually been called,
so send the `curl` calls above first: `httpbin.org` and `localhost:8080` then show up as two
separate pools.

Every source also reports **what its figures cover**, under the chart and on the home page
tile. With no provider selected this sample says `this instance only (gateway-metrics-1)` —
which is not a caveat in the documentation but part of the answer, since a number means
something different depending on which of these produced it:

```
every instance, from Prometheus
3 instances, via Redis
2 of 3 instances (the others did not answer)
```

## Choosing a source

| | Local (default) | Prometheus | Redis | Discovery |
| --- | --- | --- | --- | --- |
| Extra infrastructure | none | a Prometheus | a Redis | a service registry |
| Covers every instance | no | yes | yes | yes |
| Survives a restart | no | **yes** | no | no |
| Freshness | live | scrape interval | publish interval | live |

### Prometheus

```console
docker compose up -d prometheus
mvn spring-boot:run -Dspring-boot.run.profiles=prometheus
```

Prometheus is on http://localhost:9091 (not `:9090`, which the `auth-server` sample uses) and
scrapes `/actuator/prometheus` on the host every 5 seconds — see
[`prometheus.yml`](prometheus.yml). Give it one scrape interval before the figures appear.

The `selector: job="gateway-metrics"` matters: a shared Prometheus otherwise mixes the traffic
of every gateway publishing the same meter into one figure, wrong in a way nothing on the page
would reveal.

### Redis

```console
docker compose up -d redis
mvn spring-boot:run -Dspring-boot.run.profiles=redis
```

Each instance writes its own key and never touches the others', which is what lets them all
publish without any locking. Start a second one to watch the figures add up:

```console
mvn spring-boot:run -Dspring-boot.run.profiles=redis \
  -Dspring-boot.run.arguments="--server.port=8216 --spring.cloud.gateway.server.webflux.metrics.instance-id=gateway-metrics-2"
```

The coverage then reads `2 instances, via Redis`. Stop one and it fades out on its own when its
key expires — `time-to-live` is 45s against a 10s publish interval.

### Discovery

```console
# from spring-cloud-gateway-samples/eureka
mvn spring-boot:run
# here, twice
mvn spring-boot:run -Dspring-boot.run.profiles=discovery
mvn spring-boot:run -Dspring-boot.run.profiles=discovery -Dspring-boot.run.arguments=--server.port=8216
```

No infrastructure beyond the registry already in place: each instance is polled directly on
`/ui/metrics/local`, which answers with **its own** figures and never the consolidated ones —
the base case that keeps the instances from polling each other forever. An instance that does
not answer is left out, and the coverage says so.

That endpoint only exists once the discovery provider is selected, and it must be reachable
between instances: a gateway that secures everything has to permit it itself.

## Excluded routes

`excluded-routes` defaults to `openapi-docs-.*`, the documentation routes the OpenAPI hub
publishes. They carry contracts, not traffic, and their volume says nothing about how the
gateway is used.
