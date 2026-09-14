# gateway-passive-scan

Exercises [spring-cloud-gateway-passive-scan](../../../spring-cloud-gateway-pentest/spring-cloud-gateway-pentest-passive/README.md)
— port `8214`, with response body capture switched on.

## Run it

```console
mvn spring-boot:run
```

No infrastructure is needed: the analyser keeps its findings in memory. Send some traffic
through the gateway and watch the findings build up:

```console
curl http://localhost:8214/httpbin/get
curl "http://localhost:8214/httpbin/response-headers?token=secret"
```

## What to look at

| Url | What it shows |
| --- | --- |
| http://localhost:8214/ui/passive-scan | The findings, newest first, with a per-severity summary and SARIF/JSON/HTML export |
| http://localhost:8214/passive-scan/findings | The findings as JSON |
| http://localhost:8214/passive-scan/summary | The cumulative counts |
| http://localhost:8214/passive-scan/export?format=sarif | The SARIF report, for a CI code-scanning pipeline |

A call to `/httpbin/get` is enough to raise a `security-headers` finding: httpbin answers
without the browser-protection headers. The `token` query parameter above trips
`sensitive-query`.

## Profiles

None. The plugin needs no backend; the sample routes to `httpbin` and to `service-a`
(`:8080`), which need not be up for the analyser to work — whatever the gateway answers is
inspected.
