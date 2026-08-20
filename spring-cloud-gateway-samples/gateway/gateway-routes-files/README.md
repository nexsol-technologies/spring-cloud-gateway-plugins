# gateway-routes-files

Exercises [routes-files](../../../spring-cloud-gateway-routes/spring-cloud-gateway-routes-files/README.md)
— port `8207`: route definitions read from JSON and YAML files, the source a GitOps pipeline
reviews and deploys.

## Run it

```console
mvn spring-boot:run
```

Nothing else is needed. The `files_service_a` route points at the `service-a` sample on
`:8080`; the others reach httpbin.org.

## What to look at

Open http://localhost:8207/ui/routes — five routes, and the source column tells them apart.

| Route | Read from | Url |
| --- | --- | --- |
| `files_httpbin_get` | `classpath:gateway-routes/httpbin-routes.yaml` | http://localhost:8207/files/get |
| `files_httpbin_beta` | The same file | http://localhost:8207/files-beta/get, with `X-Beta: true` |
| `files_service_a` | `classpath:gateway-routes/service-a-routes.json` | http://localhost:8207/files-service-a/sample |
| `watched_local_route` | `file:./config/routes/local-routes.yaml` | http://localhost:8207/watched/get |
| `properties_route` | `application.yml` | http://localhost:8207/from-properties/get |

Both file formats and both predicate/filter forms — the shorthand `Path=/files/**` and the
object form with named arguments — are used across those files, since the plugin accepts them
interchangeably.

## Reloading

Edit [`config/routes/local-routes.yaml`](config/routes/local-routes.yaml), change the path to
`/watched-v2/**`, save, and call again — no restart:

```console
$ curl http://localhost:8207/watched/get      # 404
$ curl http://localhost:8207/watched-v2/get   # 200
```

**Only `file:` locations are watched.** A classpath resource is read once, at startup: editing
`src/main/resources/gateway-routes/*.yaml` changes nothing until the next restart, or until the
sources are re-read on demand:

```console
curl -X POST http://localhost:8207/actuator/refresh
```

That endpoint re-reads every source from scratch, not just the cached snapshot, and it is what
a Spring Cloud Bus `busrefresh` fans out to a whole fleet.
