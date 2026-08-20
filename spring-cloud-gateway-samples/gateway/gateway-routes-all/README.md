# gateway-routes-all

**A combination** — port `8210`: every route source of
[spring-cloud-gateway-routes](../../../spring-cloud-gateway-routes/README.md) at once —
properties, files, database, Config Server and an OpenAPI contract — aggregated into a single
route locator.

This is the sample that answers *what happens when several sources declare routes together*.
For one source on its own, see [gateway-routes-files](../gateway-routes-files/README.md).

## Run it

```console
mvn spring-boot:run
```

The database source runs on an in-memory H2, so the default profile needs nothing. The
OpenAPI source fetches the petstore contract, so an internet connection is needed for those
routes to appear.

## The five sources

| Source | Route | Url |
| --- | --- | --- |
| Properties | `properties_route` | http://localhost:8210/from-properties/get |
| Files | `files_private`, `files_public` | http://localhost:8210/files-private/get |
| Database | whatever you create | http://localhost:8210/ui/routes/db |
| OpenAPI contract | `petstore_*`, one per operation | http://localhost:8210/petstore/pet/1 |
| Config Server | `configserver_orders_route`, `configserver_billing_route` | see below |

Open http://localhost:8210/ui/routes: **the source column is the point of this sample**. Each
locator is queried individually, so every route is attributed to what it was read from, and a
route id declared by more than one source is badged as a duplicate — both definitions do
reach the route table, and the lowest order is matched first.

## Public routes

The gateway authenticates everything it routes (`user:user`), *except* the routes that
declare themselves public.
[spring-cloud-gateway-routes-security](../../../spring-cloud-gateway-routes/spring-cloud-gateway-routes-security/README.md)
replays the route predicates ahead of Spring Security — which normally runs before the route
is known — resolves the target route, and serves the request with a permissive chain when the
route is flagged.

```console
$ curl http://localhost:8210/files-private/get -o /dev/null -w '%{http_code}\n'
401
$ curl http://localhost:8210/files-public/sample -o /dev/null -w '%{http_code}\n'   # no credentials
200
```

The flag is one line of metadata, and it works whatever the source declared it:

- files — `metadata.public: true`, see [`files-routes.yaml`](src/main/resources/gateway-routes/files-routes.yaml);
- OpenAPI — `sources[].metadata.public: true`, which is why the petstore routes are open;
- database — tick **Public route** in the UI, or set the `public_route` column.

## Managing routes in the database

Open http://localhost:8210/ui/routes/db, create a route, and it takes effect immediately —
the database source reads on demand and the plugin publishes the rebuild event itself. The
same routes are available as JSON under `/api/gateway/routes`.

To run it on PostgreSQL instead of H2:

```console
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=pgsql
```

## Config Server

```console
# from spring-cloud-gateway-samples/config-server
mvn spring-boot:run
# here
mvn spring-boot:run -Dspring-boot.run.profiles=configserver
```

Two more routes appear, read from the files the Config Server serves:
http://localhost:8210/cs-orders/get and http://localhost:8210/cs-billing/get.

Change a file under `config-server/src/main/resources/config-repo/` and either wait for the
30s poll or force the reload:

```console
curl -X POST http://localhost:8210/actuator/refresh
```
