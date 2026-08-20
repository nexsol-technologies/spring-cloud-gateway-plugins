# gateway-ui

Exercises [spring-cloud-gateway-ui](../../../spring-cloud-gateway-ui/README.md) on its own —
port `8204`.

## Run it

```console
mvn spring-boot:run
```

Nothing else is needed. The routes point at httpbin.org and at the `service-a` sample, but the
views read the route *table*, not the backends: they render whether or not anything is running
behind them.

## What to look at

Open http://localhost:8204/ui.

| View | Path | What it shows here |
| --- | --- | --- |
| Home | `/ui` | Uptime, one tile per figure the active views contribute, and a link to each |
| Routes | `/ui/routes` | The three routes declared in the properties, with their predicates, filters and metadata |
| Route tester | `/ui/routes/test` | Which route would handle a described request, and why |
| Traffic | `/ui/metrics` | The per-route figures of this instance |

The routes view names the source each route came from. With no route source plugin here, every
row reads **Properties**; where several sources declare routes at once, that column is what
answers *which configuration actually won* — see
[gateway-routes-all](../gateway-routes-all/README.md).

Its two actions aim at different things: **Refresh view** re-reads the sources and re-renders
the table, this page only; **Rebuild gateway routes** publishes a `RefreshRoutesEvent`, so the
route table used to route traffic is rebuilt from the current definitions.

## What it does not show

This sample runs **only** the UI plugin, and each view activates from what is on the classpath
— so the menu has no **Database routes** entry and no **Audit** entry. Add
`spring-cloud-gateway-routes-database` and they light up on their own;
[gateway-full](../gateway-full/README.md) is the same shell with every plugin present.
