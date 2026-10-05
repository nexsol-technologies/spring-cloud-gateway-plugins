# tools

Scripts kept for the maintenance of this repository. Nothing here ships in a jar.

## console-screenshots.mjs

Re-captures the screenshots of the gateway console that the READMEs embed —
[spring-cloud-gateway-ui/doc](../spring-cloud-gateway-ui/doc) — in both themes, signing in
first when the console asks it to.

`chrome --headless --screenshot` was enough while the console was open. It is not any more: a
console running with `ui.security.mode=authenticated` answers the login page to everything, and
a command line has no way to carry the session that follows. This script signs in over HTTP,
hands the session cookie to Chrome through the DevTools Protocol, and shoots each view.

It needs **nothing installed** — Node carries the `WebSocket`, Chrome carries the protocol.
Node 22 or later, and a Chrome or Chromium.

## Run it

Start the gateway you want to photograph first. The screenshots in the READMEs come from
[gateway-full](../spring-cloud-gateway-samples/gateway/gateway-full/README.md), which runs
every plugin at once, so each view is shown with real routes and real traffic:

```console
cd spring-cloud-gateway-samples/gateway/gateway-full
mvn spring-boot:run
```

Then, from the root of the repository:

```console
node tools/console-screenshots.mjs --base=http://localhost:8181            # every view
node tools/console-screenshots.mjs --base=http://localhost:8181 --views=traffic
```

It writes `<view>-light.png` and `<view>-dark.png` into `spring-cloud-gateway-ui/doc`,
overwriting what is there, and prints each file as it goes.

## Options

| Option | Default | What it does |
| --- | --- | --- |
| `--base` | `http://localhost:8181` | Where the gateway is listening |
| `--out` | `spring-cloud-gateway-ui/doc` | Where the PNGs are written |
| `--user`, `--password` | `superadmin` / `superadmin` | The console user to sign in as; ignored when the console is open |
| `--views` | every view | Comma-separated: `home`, `collapsed`, `routes`, `routes-db`, `route-tester`, `traffic`, `instances`, `service-graph`, `service-flow`, `audit`, `openapi`,
  `insights-configuration`, `insights-profile-diff`, `insights-loggers`, `insights-beans`,
  `insights-conditions`, `insights-mappings`, `passive-scan`, `passive-scan-routes`, `forbidden`,
  `login` |
| `--themes` | `light,dark` | Which drawings to produce |
| `--width`, `--height` | `1600`, `860` | The viewport, for the views that do not ask for one of their own |
| `--settle` | `4000` | Milliseconds a view is given to draw before it is shot |
| `--port` | `9222` | The Chrome debugging port |
| `--chrome` | found automatically | Path to the Chrome binary, or set `CHROME` |

## What it takes care of

* **The session.** It signs in at `/ui/login`, carries the CSRF token the form needs, and keeps
  the cookie the authentication hands back — not the one the login page was served under, since
  signing in changes the session id. A console left open is detected on a `200` at `/ui` and
  shot anonymously.
* **The theme.** The shell reads the system preference when nothing was stored, so the script
  emulates `prefers-color-scheme` rather than driving the switch in the side menu.
* **The collapsed menu.** `collapsed` writes the side-menu state the shell remembers and
  reloads, so the menu is already narrow rather than caught mid-animation. It is published light
  only: what it shows is the width of the menu, not the palette.
* **The two states of the runtime view.** An unfolded row pushes the instances below it a
  screenful down, so the fleet and the pools of one instance cannot be in one frame. The light
  shot keeps every row closed, which is the fleet table; the dark one opens them all, for the
  pool tables.
* **The login page.** Shot without a session, which is the only way to see it.
* **The frame each view needs.** Most are shot in the viewport above. The five introspection
  views that list rows take a taller one, and the passive scan view is published whole rather
  than as a first screenful &mdash; its coverage table, summary and findings are read together.
  Those sizes live in the `VIEWS` table of the script, so one run reproduces every published
  drawing; `--width` and `--height` move only the views that ask for nothing else.

## What it cannot take care of

The *content* of a view is whatever the gateway you pointed it at happens to hold. A capture run
against a bare `gateway-full` shows no calls, no audited exchanges and no database routes, which
makes for poorer screenshots than the ones in the repository.

For a faithful run, bring the environment up first — the
[`eureka`](../spring-cloud-gateway-samples/eureka) and
[`service-a`](../spring-cloud-gateway-samples/service-a) samples, the `eureka` profile, the
routes in the database — and send some traffic through the gateway before shooting.

## demo-site.mjs

Assembles the pages published to
[GitHub Pages](https://nexsol-technologies.github.io/spring-cloud-gateway-plugins/) — the views
of the console that are worth watching rather than photographing. A README can embed a drawing;
it cannot run one.

```console
node tools/demo-site.mjs --version=1.19.0        # writes target/demo-site
open target/demo-site/flow.html
```

| Option | Default | What it does |
| --- | --- | --- |
| `--out` | `target/demo-site` | Where the assembled site is written; the directory is emptied first |
| `--version` | `SNAPSHOT` | The version the pages report at their foot |

**Nothing in those pages is a copy of the console.** The stylesheet and the scripts are the
module's own files, taken as they are, and the markup of a view is read out of its Thymeleaf
template with the `th:` attributes dropped — so a view changed in the module is a demo changed
with it, and there is no second rendering of the console to keep in step. The script fails
rather than guessing when a template no longer carries the slots it reads.

What the pages supply is the one thing a published page cannot have: a gateway answering.
`fetch` is stubbed in the page itself, over invented traffic that advances on its own, and each
page says so where it cannot be missed. Adding a view means adding its page to
[tools/demo](demo) and its entry to the `PAGES` table of the script.

They are published by
[publish-demo-pages.yml](../.github/workflows/publish-demo-pages.yml), which the release
workflow calls so that a release refreshes the site, and which can be started on its own when
the site has to go out without one. A deployment replaces the site whole, so nothing else may
publish to Pages.

## docs-site.mjs

Gathers the READMEs git tracks into `target/docs-src`, from which MkDocs builds the
documentation published under
[docs/](https://nexsol-technologies.github.io/spring-cloud-gateway-plugins/docs/) on the same
site, with a navigation per module and a search.

```console
node tools/demo-site.mjs                          # first: it empties target/demo-site
node tools/docs-site.mjs                          # writes target/docs-src
pip install 'mkdocs<2' 'mkdocs-material==9.*'
mkdocs build --strict -f tools/mkdocs.yml         # writes target/demo-site/docs
```

| Option | Default | What it does |
| --- | --- | --- |
| `--out` | `target/docs-src` | Where the READMEs are gathered; the directory is emptied first |

A link between READMEs and a link to a screenshot work on the site as they do on GitHub. A link
to any other file — a `docker-compose.yml`, a sample directory without a README — is pointed at
that file on GitHub, on `main`. A link to a file that does not exist fails the script, and a
link to a heading that does not exist fails `mkdocs build --strict`: write anchors the way
GitHub derives them, and both sides agree.

The site is built by the same
[publish-demo-pages.yml](../.github/workflows/publish-demo-pages.yml) as the demo pages, since
a deployment replaces the site whole.
