# tools

Scripts kept for the maintenance of this repository. Nothing here ships in a jar.

## console-screenshots.mjs

Re-captures the screenshots of the gateway console that the READMEs embed &mdash;
[spring-cloud-gateway-ui/doc](../spring-cloud-gateway-ui/doc) &mdash; in both themes, signing
in first when the console asks it to.

### Why it exists

`chrome --headless --screenshot` was enough while the console was open. It is not any more:
a console running with `ui.security.mode=authenticated` answers the login page to
everything, and a command line has no way to carry the session that follows. This script
signs in over HTTP, hands the session cookie to Chrome through the DevTools Protocol, and
shoots each view.

It needs **nothing installed**: Node carries the `WebSocket`, Chrome carries the protocol.
Node 22 or later, and a Chrome or Chromium.

### Running it

Start the gateway you want to photograph first. The screenshots in the READMEs come from
[gateway-full](../spring-cloud-gateway-samples/gateway/gateway-full), which runs every plugin
at once, so each view is shown with real routes and real traffic:

```console
cd spring-cloud-gateway-samples/gateway/gateway-full
mvn spring-boot:run
```

Then, from the root of the repository:

```console
node tools/console-screenshots.mjs --base=http://localhost:8181
```

It writes `<view>-light.png` and `<view>-dark.png` into `spring-cloud-gateway-ui/doc`,
overwriting what is there, and prints each file as it goes.

### Options

| Option | Default | |
| --- | --- | --- |
| `--base` | `http://localhost:8181` | Where the gateway is listening |
| `--out` | `spring-cloud-gateway-ui/doc` | Where the PNGs are written |
| `--user`, `--password` | `superadmin` / `superadmin` | The console user to sign in as. Ignored when the console is open |
| `--views` | every view | Comma-separated: `home`, `collapsed`, `routes`, `routes-db`, `route-tester`, `traffic`, `instances`, `service-graph`, `audit`, `openapi`, `forbidden`, `login` |
| `--themes` | `light,dark` | Which drawings to produce |
| `--width`, `--height` | `1280`, `860` | The viewport |
| `--settle` | `4000` | Milliseconds a view is given to draw before it is shot |
| `--port` | `9222` | The Chrome debugging port |
| `--chrome` | found automatically | Path to the Chrome binary, or set `CHROME` |

Redoing a single view after a CSS change:

```console
node tools/console-screenshots.mjs --base=http://localhost:8181 --views=traffic
```

### What it takes care of

* **The session.** It signs in at `/ui/login`, carries the CSRF token the form needs, and
  keeps the cookie the authentication hands back &mdash; not the one the login page was
  served under, since signing in changes the session id. A console left open is detected on
  a `200` at `/ui` and shot anonymously.
* **The theme.** The shell reads the system preference when nothing was stored, so the
  script emulates `prefers-color-scheme` rather than driving the switch in the side menu.
* **The collapsed menu.** `collapsed` writes the side-menu state the shell remembers and
  reloads, so the menu is already narrow rather than caught mid-animation. It is published
  light only: what it shows is the width of the menu, not the palette.
* **The login page.** Shot without a session, which is the only way to see it.

### What it cannot take care of

The *content* of a view is whatever the gateway you pointed it at happens to hold. A capture
run against a bare `gateway-full` shows no calls, no audited exchanges and no database
routes, which makes for poorer screenshots than the ones in the repository. For a faithful
run, bring the environment up first &mdash; the [`eureka`](../spring-cloud-gateway-samples/eureka)
and [`service-a`](../spring-cloud-gateway-samples/service-a) samples, the `eureka` profile,
the routes in the database &mdash; and send some traffic through the gateway before shooting.
