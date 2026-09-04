# gateway-full-native

Every plugin that contributes ahead-of-time hints, built as a **GraalVM native image** — port
`8223`. This is the build the hints exist for: a route argument bound reflectively, an OpenAPI
contract parsed reflectively and a reading serialized reflectively fail here, and nowhere else,
when their type carries no hint.

## Run it

Needs a GraalVM distribution on `JAVA_HOME` (`sdk install java 25-graalce`, or the `graalvm`
distribution of `actions/setup-java`).

```console
mvn -DskipTests install                     # from the repository root, once
mvn -Pnative -DskipTests -pl spring-cloud-gateway-samples/gateway/gateway-full-native native:compile
./spring-cloud-gateway-samples/gateway/gateway-full-native/target/sample-gateway-full-native
```

The `native` profile runs `spring-boot:process-aot` first, which is what writes the
reachability metadata the image is built from, and turns on the GraalVM reachability metadata
repository for the libraries that publish their own.

Building the image takes minutes and several gigabytes of memory. The
[`build-native`](../../../.github/workflows/build-native.yml) workflow does it on every push, so
a plugin that stops being buildable natively is caught there rather than by whoever tries next.

## What to look at

| What | Url |
| --- | --- |
| The console | http://localhost:8223/gateway-console |
| The routes the gateway built | http://localhost:8223/actuator/gateway/routes |
| Health | http://localhost:8223/actuator/health |

Every route of this sample carries a filter whose arguments are bound reflectively, so a route
list that is complete is what says the hints are complete:

```console
curl -s localhost:8223/actuator/gateway/routes | grep -o '"route_id":"[^"]*"'
```

## Profiles

`native`, and nothing else — it is the profile that builds the image. See
[gateway-full-cache-aot](../gateway-full-cache-aot/README.md) and
[gateway-full-aot-jvm](../gateway-full-aot-jvm/README.md) for the two ahead-of-time builds that
stay on the JVM.
