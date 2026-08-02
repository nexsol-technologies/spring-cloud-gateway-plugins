# gateway-ui

Exercises [spring-cloud-gateway-ui](../../../spring-cloud-gateway-ui/README.md) on its own, on
port `8204`.

## Running it

```console
mvn spring-boot:run
```

Nothing else is needed. The routes point at httpbin.org and at the `service-a` sample, but
the views read the route *table*, not the backends — they render whether or not anything is
running behind them.

## What it shows

Open http://localhost:8204/ui.

| View | Path | What it shows here |
| --- | --- | --- |
| Home | `/ui` | uptime, one tile per figure the active views contribute, and a link to each |
| Routes | `/ui/routes` | the three routes declared in the properties, with their predicates, filters and metadata |
| Route tester | `/ui/routes/test` | which route would handle a described request, and why |
| Traffic | `/ui/metrics` | the per-route figures of this instance |

## What it does not show

This sample runs **only** the UI plugin. Each view activates from what is on the classpath,
so the menu here has no **Database routes** entry and no **Audit** entry.
Add `spring-cloud-gateway-routes-database` and they light up on their own — the
[gateway-full](../gateway-full/README.md) sample is the same shell with every plugin present.

## Attribution

The routes view names the source each route came from. With no route source plugin here,
every row reads **Properties**. Where several sources declare routes at once, that column is
what answers *which configuration actually won* — see
[gateway-routes-all](../gateway-routes-all/README.md).

## The two refresh actions

They are named after what they refresh, because they aim at different things:

- **Refresh view** re-reads the sources and re-renders the table. This page only.
- **Rebuild gateway routes** publishes a `RefreshRoutesEvent`, so the route table used to
  route traffic is rebuilt from the current definitions.
