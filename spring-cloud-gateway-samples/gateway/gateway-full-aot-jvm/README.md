# gateway-full-aot-jvm

Every plugin that contributes ahead-of-time hints, built with **Spring ahead-of-time processing
on the JVM** — port `8222`. The bean definitions are generated at build time and replayed at
start-up, with no reflection over the configuration classes.

## Run it

```console
mvn -DskipTests install                     # from the repository root, once
mvn -pl spring-cloud-gateway-samples/gateway/gateway-full-aot-jvm package
java -Dspring.aot.enabled=true -jar \
     spring-cloud-gateway-samples/gateway/gateway-full-aot-jvm/target/sample-gateway-full-aot-jvm-*.jar
```

`process-aot` is bound in this module's `pom.xml`, so `package` generates the sources; the
property is what makes the run use them. Without it the same jar starts the ordinary way.

Measured on this sample, JDK 25, Apple silicon:

| Start | Time to `Started` |
| --- | --- |
| Plain | 2.42 s |
| `-Dspring.aot.enabled=true` | 1.93 s |

**The conditions are evaluated during the build, not at start-up.** Every plugin of this
repository is gated on a property, so a jar processed with `metrics.provider=redis` cannot be
switched to `prometheus` afterwards: build with the profile and the properties the application
will run with.

## What to look at

| What | Url |
| --- | --- |
| The console | http://localhost:8222/gateway-console |
| The routes the gateway built | http://localhost:8222/actuator/gateway/routes |
| Health | http://localhost:8222/actuator/health |

The generated metadata is worth a look too — it is the same file a native image is built from:

```console
cat spring-cloud-gateway-samples/gateway/gateway-full-aot-jvm/target/spring-aot/main/resources/META-INF/native-image/*/*/reachability-metadata.json
```

## Profiles

None. See [gateway-full-cache-aot](../gateway-full-cache-aot/README.md) for the JDK AOT cache,
which stacks with this one, and [gateway-full-native](../gateway-full-native/README.md) for the
native image.
