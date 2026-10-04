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

Three entries of that repository are carried by this sample instead, under
`src/main/resources/META-INF/native-image/`: `logback-classic`, `netty-common` and `spring-aop`,
excluded from the repository in the `pom.xml` and copied here minus nineteen members. GraalVM
25.0.4 refuses a conditional entry naming a member the class does not have — Logback's
`valueOf(String)` probes, Netty's `NativePRNG()` and `DirectByteBuffer(long, int)`, a method
Spring 7 dropped — and the repository still ships them
([oracle/graal#13316](https://github.com/oracle/graal/issues/13316),
[graalvm-reachability-metadata#10117](https://github.com/oracle/graalvm-reachability-metadata/issues/10117)).
Excluding a whole entry is not an option: Logback alone needs a hundred of its members at the
first log line. Once a repository release carries the fix, delete the three files and the three
exclusions.

The image is not built with `--exact-reachability-metadata`, which would turn every reflective
read of an unregistered member into an error: Spring probes classes that do not exist by design
— `LogLevelEditor`, looked up by the `<Type>Editor` convention, is the first one hit — and the
image dies at start-up on the first of them. What the checks below rely on instead is the
behaviour of a missing hint: a route not built, a reading not answered, a page not rendered.

Building the image takes minutes and several gigabytes of memory. The
[`build-native`](../../../.github/workflows/build-native.yml) workflow does it on every pull request and on every push to `main`, so
a plugin that stops being buildable natively is caught there rather than by whoever tries next.

## What to look at

| What | Url |
| --- | --- |
| The console | http://localhost:8223/ui |
| The routes the gateway built | http://localhost:8223/actuator/gateway/routes |
| Health | http://localhost:8223/actuator/health |
| The passive-scan readings | http://localhost:8223/passive-scan/routes |
| The scanner coverage | http://localhost:8223/passive-scan/coverage |

The actuator's gateway endpoints, the metrics, the audit tail and the findings sit behind the
sample's one user, `sample` / `sample`; the home page, the login page and the passive-scan
readings are open.

Every route of this sample carries a filter whose arguments are bound reflectively, so a route
list that is complete is what says the hints are complete:

```console
curl -s -u sample:sample localhost:8223/actuator/gateway/routes | grep -o '"route_id":"[^"]*"'
```

The passive-scan readings are the second check: they are records Jackson writes reflectively,
and one without a hint has no property for Jackson to find, so the reading does not come back.
The contract is the third: it is read on the first exchange of its route, through the OpenAPI
model and the mix-ins of swagger-core, so a path the contract does not declare is refused with
a 400 — whereas a contract that could not be read is forwarded, to the closed port behind it.

```console
curl -s localhost:8223/passive-scan/coverage
curl -s -o /dev/null -w '%{http_code}' -u sample:sample localhost:8223/bookstore/nope   # 400
```

## Profiles

`native`, and nothing else — it is the profile that builds the image. See
[gateway-full-cache-aot](../gateway-full-cache-aot/README.md) and
[gateway-full-aot-jvm](../gateway-full-aot-jvm/README.md) for the two ahead-of-time builds that
stay on the JVM.
