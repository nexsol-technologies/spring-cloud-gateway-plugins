# gitops

A reference CI/CD chain publishing route definitions into a Spring Cloud Config Server, which
the [routes-configserver](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-configserver/README.md)
plugin then serves to the gateway. Teams own route files in a Bitbucket repository they can see;
a Jenkins job validates them, proves they start, aggregates them, and pushes the result into a
configuration repository they never see.

Nothing here is built or released — no `pom.xml`, no module in the reactor. These are template
files meant to be copied into other repositories and have their placeholders (`acme.corp`,
`@org/*`, `registry.acme.corp`, `bitbucket.acme.corp:7999`) replaced.

## The chain

```
  ┌─ gateway-routes-external.git ──────────────── Bitbucket, developers work here
  │    routes/orders.yaml          team-commande
  │    routes/billing.yaml         team-facturation
  │    policy.yaml                 secops
  │    Jenkinsfile                 platform
  │
  │  pull request  ──────────────► Validate + Smoke. Nothing is published.
  │  merge on dev|cert|form|prod ► Validate + Smoke + Aggregate + Publish
  ▼
  ┌─ gwcfg/external.git ───────────────────────── no developer sees it
  │    dist/routes.yaml            the aggregate, one commit per build, by jenkins
  │    application.yml             written once, never again
  │  branches: dev, cert, form, prod
  ▼
  ┌─ Config Server ────────────────────────────── uri: .../gwcfg/{application}.git
  │    GET /external/default/prod/dist/routes.yaml
  ▼
  ┌─ the gateway ──────────────────────────────── spring.application.name: external
  │    routes-configserver polls every 30s
  ▼
  RouteDefinition in the locator
```

Three coordinates carry the whole design:

| Config Server coordinate | Bound to | Set by |
| --- | --- | --- |
| `{application}` | the gateway | `spring.application.name` on the gateway; selects the repo `gwcfg/<name>.git` |
| `{label}` | the environment | the git branch, both in the route repo and in the config repo |
| `{profile}` | unused | pinned to `default` |

Adding the eleventh gateway therefore creates two repositories and changes no configuration
anywhere: not on the Config Server, not on the other gateways.

## Files

| File | Lines | What it is |
| --- | --- | --- |
| `gateway-routes-lib/vars/gatewayRoutes.groovy` | 147 | The shared library steps — the CPS side, everything that touches a Jenkins step |
| `gateway-routes-lib/src/com/acme/gateway/RouteRules.groovy` | 245 | The rules — `@NonCPS`, pure, no I/O, unit-testable outside Jenkins |
| `gateway-routes-external/Jenkinsfile` | 96 | The pipeline. Identical in every route repository except its `environment` block |
| `gateway-routes-external/policy.yaml` | 22 | The security contract of one gateway |
| `gateway-routes-external/CODEOWNERS` | 6 | Teams own `routes/`, secops owns `policy.yaml` |
| `gateway-routes-external/routes/orders.yaml` | 13 | One team's route file, as an example of the expected shape |
| `runtime/config-server-application.yml` | 27 | The Config Server, serving one repository per gateway |
| `runtime/gwcfg-application.yml` | 31 | The `application.yml` at the root of every `gwcfg/<gw>.git` |

## Requirements

* Jenkins with the **Pipeline Utility Steps** plugin (`readYaml`, `readJSON`, `writeYaml`,
  `findFiles`) and the **SSH Agent** plugin (`sshagent`).
* The shared library registered globally under the name `gateway-routes`
  (`@Library('gateway-routes')` in the Jenkinsfile).
* An SSH credential with write access to the `gwcfg/*` repositories, referenced by
  `CONFIG_CRED`.
* A Docker daemon on the agent, for the `Smoke` stage.
* Multibranch jobs configured to build **the merge of the PR with the target branch**, not the
  PR head. On the PR head, two pull requests that collide with each other both go green and the
  failure only appears after the merge.

## Setting up a new gateway

1. Create `gwcfg/<gw>.git` with branches `dev`, `cert`, `form`, `prod`, and drop
   `runtime/gwcfg-application.yml` at the root of each — byte for byte identical across every
   gateway.
2. Create `gateway-routes-<gw>.git` from `gateway-routes-external/` as a template.
3. In its `Jenkinsfile`, edit the `environment` block, and nothing else:

   ```groovy
   GATEWAY          = 'external'                                     // {application} + gwcfg repo name
   SMOKE_IMAGE      = 'registry.acme.corp/apigw/edge-gateway:1.15.0' // the full coordinate, as deployed
   CONFIG_REPO_BASE = 'ssh://git@bitbucket.acme.corp:7999/gwcfg'
   CONFIG_CRED      = 'gateway-config-deploy-key'
   ENVIRONMENTS     = 'dev cert form prod'
   ```

4. Write its `policy.yaml` — this is the part that genuinely differs between an internal gateway
   and an internet-facing one.
5. Set `spring.application.name` on the gateway itself to the same value as `GATEWAY`, and
   `spring.cloud.config.label` to its environment.

## What a team commits

A file under `routes/`, at any depth, `.yaml`, `.yml` or `.json`. Same format as the
[file source](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-files/README.md#file-format):

```yaml
routes:
  - id: orders_public
    uri: lb://ORDERS
    predicates:
      - Path=/api/orders/**
      - Host=api.acme.com
    filters:
      - CorrelationId
      - name: AuthorizationToken
        args:
          scope: orders.read
    metadata:
      owner: team-commande
```

`metadata.owner` is mandatory — it is the only rule that survives a team reorganisation.
Files are read in sorted path order and the aggregate preserves it.

## policy.yaml

One per gateway, owned by secops in `CODEOWNERS`. Every key is optional; an absent key
disables its rule.

| Key | Effect |
| --- | --- |
| `uri.requireTls` | Rejects any `uri` starting with `http://` |
| `uri.allow` | List of regexes; a `uri` matching none of them is rejected |
| `filters.required` | Filter names every route must declare |
| `filters.forbidden` | Filter names no route may declare |
| `metadata.approvedPublic` | Route ids allowed to carry `metadata.public: true` |

`metadata.public: true` exempts a route from Spring Security through
[routes-security](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-security/README.md),
which is why it needs an id-by-id approval rather than a rule.

## The rules

`RouteRules.all()` runs all eight and returns every error at once — one round trip per pull
request, not one per rule.

| Rule | Rejects |
| --- | --- |
| `schema` | A route without `id`, `uri` or `predicates`; an id that is not a lowercase slug; a non-numeric `order` |
| `uniqueIds` | Two routes sharing an id, whatever the file or the team |
| `pathCollisions` | A `Path=` pattern of one file shadowing another file's, on an intersecting `Host=` |
| `uriAllowlist` | A target outside `policy.uri.allow`, or plaintext when `requireTls` |
| `filters` | A missing `policy.filters.required`, or a declared `policy.filters.forbidden` |
| `publicMetadata` | `metadata.public: true` on an id absent from `policy.metadata.approvedPublic` |
| `owner` | A route with no `metadata.owner` |
| `noInlineSecrets` | Inline Basic credentials, a Bearer JWT, a `client-secret`/`password`/`api-key`, or credentials in the uri |

Two behaviours worth knowing before changing them:

* `pathCollisions` compares the **literal prefix** of a pattern (everything before the first
  `*`, `{` or `?`, cut back to a segment boundary), so it catches `/api/**` shadowing
  `/api/orders/**` but not full pattern equivalence. It only compares routes from **different
  files**: inside one file the author controls `order` and is allowed to overlap deliberately.
* `predicateValues` and `filterNames` accept both the shorthand (`Path=/a/**`) and the object
  form (`name: Path, args: {pattern: /a/**}`), including Spring's positional `_genkey_0` keys.
  Any new rule reading a predicate must go through them.

## The pipeline

| Stage | Runs on | Does |
| --- | --- | --- |
| `Validate` | every build | Reads `policy.yaml`, loads `routes/`, runs the eight rules, returns the entries |
| `Smoke` | every build | Starts `SMOKE_IMAGE` on the route files and compares the routes the gateway loaded against the number declared |
| `Publish` | merges on an environment branch only | Aggregates into `dist/routes.yaml`, archives it, pushes it onto the matching branch of `gwcfg/<gw>.git` |

`Smoke` is the only check that proves the files *parse*: a malformed `filters:` block fails the
gateway's boot and no static rule will ever see it. It turns `routes-configserver` off, turns
`routes-files` on, and mounts `routes/` read-only. The parser under test is the production one:
`ConfigServerRouteDefinitionLoader` imports `RouteDefinitionFileParser` from the `routes-files`
module, so both sources go through the same code.

`publish` guards the blast radius before pushing: it refuses an empty aggregate outright, and
refuses one that retains less than half the previously published routes unless the build is
re-run with `ALLOW_SHRINK`. The aggregate is a full regeneration, never a merge, so a route file
deleted upstream does disappear.

On failure of an environment branch, the `post` block alerts: nothing was pushed, so the
environment is not broken — it is **frozen** on its previous routes, and will stay there
silently until someone acts. That silent freeze is the failure mode the alert exists for.

## Config Server wiring

`runtime/config-server-application.yml` points one URI at every repository:

```yaml
spring.cloud.config.server.git.uri: ssh://git@bitbucket.acme.corp:7999/gwcfg/{application}.git
```

Verified in `MultipleJGitEnvironmentRepository` (spring-cloud-config-server 5.0.5):
`getRepository()` substitutes `{application}`, `{profile}` and `{label}` as soon as the URI
contains a `{`, and `getLocations()` — the path the plain-text resource endpoint takes — goes
through the same method. So no `repos:` block, and no Config Server restart when a gateway is
added. `clone-on-start: false` is required here: the repository set is open-ended.

On the gateway side, `runtime/gwcfg-application.yml` resolves both coordinates from the
gateway's own identity:

```yaml
config-server:
  name: ${spring.application.name}
  profile: default
  label: ${spring.cloud.config.label}
  files:
    - dist/routes.yaml
```

The plugin builds `{uri}/{name}/{profile}/{label}/{file}` (verified in
`ConfigServerRouteDefinitionLoader.buildConfigServerUrls`), and picks the parser from the file
extension — hence `.yaml`. The label segment is omitted entirely when `label` is unset, which
silently changes the URL: leave it set.

## Propagation

The gateway polls every 30 seconds (`update-interval: 30s`). There is no webhook, no Spring
Cloud Bus, and no call to `/actuator/refresh` at the end of the pipeline: the actuators are not
reachable from outside the Docker Swarm, and Jenkins is outside it. The consequence to keep in
mind is that **the pipeline cannot report that a route is live** — it reports that the aggregate
was pushed.

Two mechanics behind that choice:

* The aggregate has a fixed name (`dist/routes.yaml`) in every gateway and every environment,
  so the polled file list never changes and polling is sufficient.
* `update-interval` is read once, into a final field of `RouteConfigServerLifecycle`, so
  changing it *does* require a restart — unlike the file list, which
  `ConfigServerRouteDefinitionLoader.load()` re-resolves on every subscription since `8687b54`.

## Known gaps

1. **`RouteRules.groovy` has no test and has never been compiled** — there was no `groovy` on
   the machine when it was written. It is the code that decides what reaches production. Since
   every method is `@NonCPS` and pure, a plain Groovy/Spock suite outside Jenkins is enough;
   this is the first thing to do before anyone relies on it.
2. **This `Jenkinsfile` is a standalone `pipeline { }`.** The real gateway of the organisation
   delegates everything to `@Library('dsi-pipeline@v2')` / `pipelineMaven`. How the two combine
   — a `pipelineRoutes` in the same shared library, or this pipeline kept separate — has never
   been looked at.
3. `mail` in the `post` block is commented out; wire it to the real alerting.
4. The `Smoke` image must accept Spring arguments on the command line. If it freezes its
   configuration in its `CMD`, or takes it from environment variables only, pass the same values
   as `-e SPRING_CLOUD_GATEWAY_...` instead. (Its classpath is not a concern:
   `routes-configserver` depends on `routes-files`, so the file source is always present — only
   disabled by default.)

## Related

* [routes-configserver](../spring-cloud-gateway-routes/spring-cloud-gateway-routes-configserver/README.md)
  — the plugin that consumes what this chain publishes.
* [Refreshing routes](../spring-cloud-gateway-routes/README.md#refreshing-routes) — the reload
  triggers, including the `/actuator/refresh` path this design does not use.
