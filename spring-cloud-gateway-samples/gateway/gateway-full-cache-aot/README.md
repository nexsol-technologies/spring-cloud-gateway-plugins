# gateway-full-cache-aot

Every plugin that contributes ahead-of-time hints, built to start against a JDK **AOT cache**
— port `8221`. Nothing in the code is specific to it: a cache records what a first run loaded
and replays it on the next.

## Run it

```console
mvn -DskipTests install                     # from the repository root, once
mvn -pl spring-cloud-gateway-samples/gateway/gateway-full-cache-aot package
cd spring-cloud-gateway-samples/gateway/gateway-full-cache-aot/target
```

A cache is written by a run that **ends by itself**, which a gateway otherwise never does.
`--sample.training-run=true` closes the context as soon as the gateway is up:

```console
java -XX:AOTCacheOutput=gateway.aot -jar sample-gateway-full-cache-aot-*.jar \
     --server.port=0 --sample.training-run=true
java -XX:AOTCache=gateway.aot -jar sample-gateway-full-cache-aot-*.jar
```

The one-step `-XX:AOTCacheOutput` needs **JDK 24 or later** (JDK 25 here). On an older JDK the
equivalent is the two-step AppCDS: `-XX:ArchiveClassesAtExit=gateway.jsa`, then
`-XX:SharedArchiveFile=gateway.jsa`.

Measured on this sample, JDK 25, Apple silicon:

| Start | Time to `Started` |
| --- | --- |
| Plain | 2.94 s |
| `-XX:AOTCache=gateway.aot` | 1.58 s |

The cache holds the loaded classes, nothing else, so it is rebuilt whenever the application or
its dependencies change — a stale cache is ignored, not honoured.

## What to look at

| What | Url |
| --- | --- |
| The console | http://localhost:8221/gateway-console |
| The routes the gateway built | http://localhost:8221/actuator/gateway/routes |
| Health | http://localhost:8221/actuator/health |

Nothing else is needed: the sample carries no registry, Redis, Kafka or authorization server,
so it starts on its own.

## Profiles

None. See [gateway-full-aot-jvm](../gateway-full-aot-jvm/README.md) for the Spring
ahead-of-time build on the JVM, which stacks with this cache, and
[gateway-full-native](../gateway-full-native/README.md) for the native image.
