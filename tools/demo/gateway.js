/*
 * A gateway with traffic on it, invented, behind a stubbed `fetch`.
 *
 * Every demo page loads this before the script of its view. The view then reads the
 * endpoints it reads in a gateway — /ui/metrics/data, /ui/service-graph/data, /ui/audit/events,
 * and so on — and gets answers in the shapes the gateway's controllers produce, all drawn
 * from one simulation: the routes the traffic view plots are the ones the flow carries, the
 * audit tail lists their calls, and the passive findings grow with the traffic of the route
 * they were raised on.
 *
 * Time moves with the clock: each request advances the simulation by the time since the
 * previous one, so a view polling every three seconds and one polling every five see the
 * same gateway. Half an hour of history is played before the first answer, so no view
 * opens on an empty gateway.
 */
(function () {
	'use strict';

	/* The gateway ---------------------------------------------------------------------- */

	var ROUTES = [
		{ id: 'service-a', uri: 'http://localhost:8080', path: '/service-a/**', latency: 34 },
		{ id: 'service-b', uri: 'http://localhost:8081', path: '/service-b/**', latency: 61 },
		{ id: 'orders', uri: 'http://orders:8080', path: '/api/orders/**', latency: 48 },
		{ id: 'payments', uri: 'https://payments:8443', path: '/api/payments/**', latency: 172 },
		{ id: 'catalog', uri: 'http://catalog:8080', path: '/api/catalog/**', latency: 21 }
	];

	/*
	 * Who calls which route, how many calls a second on average, and how often it fails. A
	 * flow where everything is green never shows what the colours are for, so two callers
	 * get 4xx and one gets the odd 5xx.
	 */
	var EDGES = [
		{ from: 'frontend', route: 'service-a', rate: 2.6, clientErrors: 0.01, errors: 0 },
		{ from: 'frontend', route: 'catalog', rate: 3.8, clientErrors: 0.02, errors: 0 },
		{ from: 'mobile-app', route: 'catalog', rate: 2.2, clientErrors: 0.01, errors: 0 },
		{ from: 'mobile-app', route: 'orders', rate: 1.6, clientErrors: 0.03, errors: 0 },
		{ from: 'checkout-web', route: 'orders', rate: 1.2, clientErrors: 0.02, errors: 0 },
		{ from: 'checkout-web', route: 'payments', rate: 0.9, clientErrors: 0.04, errors: 0.004 },
		{ from: 'partner-api', route: 'orders', rate: 0.5, clientErrors: 0.18, errors: 0 },
		{ from: 'service-a', route: 'service-b', rate: 1.1, clientErrors: 0, errors: 0.002 },
		{ from: 'batch-loader', route: 'service-b', rate: 0.6, clientErrors: 0.01, errors: 0.03 }
	];

	/* Who stands behind a caller, as the audit records them. */
	var CALLERS = {
		'frontend': { users: ['alice', 'bob', 'chloe'], ip: '10.20.1.' },
		'mobile-app': { users: ['dave', 'emma', 'farid'], ip: '10.20.4.' },
		'checkout-web': { users: ['gina', 'hugo'], ip: '10.20.2.' },
		'partner-api': { users: [null], ip: '192.0.2.' },
		'service-a': { users: [null], ip: '10.10.0.' },
		'batch-loader': { users: ['batch'], ip: '10.10.9.' }
	};

	/* What a call to a route asks for: a method and a path. */
	var REQUESTS = {
		'service-a': [['GET', '/service-a/hello'], ['GET', '/service-a/profile/{n}']],
		'service-b': [['GET', '/service-b/items/{n}'], ['PUT', '/service-b/items/{n}']],
		'orders': [['GET', '/api/orders/{n}'], ['GET', '/api/orders'], ['POST', '/api/orders']],
		'payments': [['POST', '/api/payments'], ['GET', '/api/payments/{n}']],
		'catalog': [['GET', '/api/catalog/products/{n}'], ['GET', '/api/catalog/search']]
	};

	var STATUS = {
		200: 'OK', 201: 'CREATED', 400: 'BAD_REQUEST', 401: 'UNAUTHORIZED', 404: 'NOT_FOUND',
		429: 'TOO_MANY_REQUESTS', 500: 'INTERNAL_SERVER_ERROR', 502: 'BAD_GATEWAY',
		503: 'SERVICE_UNAVAILABLE'
	};

	var INSTANCES = [
		{ id: 'gateway-1', uri: 'http://10.0.0.5:8080', share: 0.55, bootedSecondsAgo: 273600 },
		{ id: 'gateway-2', uri: 'http://10.0.0.6:8080', share: 0.45, bootedSecondsAgo: 94200 }
	];

	var COVERAGE = 'demo data — nothing is being polled';
	var AUDIT_CAPACITY = 500;

	function route(id) {
		return ROUTES.filter(function (candidate) {
			return candidate.id === id;
		})[0];
	}

	function service(routeId) {
		return route(routeId).uri.replace(/^[a-z]+:\/\//, '');
	}

	function pick(list) {
		return list[Math.floor(Math.random() * list.length)];
	}

	function hex(length) {
		var text = '';
		while (text.length < length) {
			text += Math.floor(Math.random() * 16).toString(16);
		}
		return text;
	}

	/* The state ------------------------------------------------------------------------ */

	var started = Date.now();
	var now = started - 30 * 60 * 1000;
	var traffic = {};
	ROUTES.forEach(function (each) {
		traffic[each.id] = { count: 0, totalMs: 0, maxMs: 0, clientErrorCount: 0, errorCount: 0, recent: 0 };
	});
	EDGES.forEach(function (edge) {
		edge.calls = 0;
		edge.clientErrorCalls = 0;
		edge.errorCalls = 0;
	});
	var audit = [];

	/*
	 * The findings the passive analyser raised. `rate` is the share of the route's calls
	 * that raise the finding again: a missing header is on every response, a stack trace
	 * only on the errors that leak one.
	 */
	var FINDINGS = [
		{ scanner: 'basic-auth', severity: 'CRITICAL', confidence: 'HIGH', category: 'API2', cwe: 'CWE-319',
			method: 'POST', path: '/api/orders', routeId: 'orders', rate: 0.04,
			title: 'Basic credentials sent over cleartext HTTP',
			detail: 'An Authorization: Basic header reached the gateway on a plain HTTP connection: the credentials travel readable to anyone on the path.',
			remediation: 'Terminate TLS in front of the gateway and refuse Basic credentials on cleartext connections.',
			reference: 'https://owasp.org/API-Security/editions/2023/en/0xa2-broken-authentication/',
			evidence: { scheme: 'http', header: 'Authorization: Basic cGFydG5lcjo…' } },
		{ scanner: 'cors', severity: 'HIGH', confidence: 'HIGH', category: 'API8', cwe: 'CWE-942',
			method: 'GET', path: '/api/orders/{id}', routeId: 'orders', rate: 0.3,
			title: 'CORS allows any origin with credentials',
			detail: 'The response reflects the Origin of the request and allows credentials, so any site can read it with the user\'s cookies.',
			remediation: 'Allow a fixed list of origins, and never combine a reflected origin with Access-Control-Allow-Credentials.',
			reference: 'https://owasp.org/API-Security/editions/2023/en/0xa8-security-misconfiguration/',
			evidence: { 'Access-Control-Allow-Origin': 'https://evil.example', 'Access-Control-Allow-Credentials': 'true' } },
		{ scanner: 'pii-in-url', severity: 'HIGH', confidence: 'MEDIUM', category: 'API3', cwe: 'CWE-598',
			method: 'GET', path: '/service-a/profile/{id}', routeId: 'service-a', rate: 0.05,
			title: 'E-mail address in the query string',
			detail: 'A query parameter carries an e-mail address. URLs end up in access logs, proxies and browser history.',
			remediation: 'Move personal data to the request body or to a header.',
			reference: 'https://owasp.org/API-Security/editions/2023/en/0xa3-broken-object-property-level-authorization/',
			evidence: { parameter: 'email', value: 'a***@example.com' } },
		{ scanner: 'jwt-weakness', severity: 'MEDIUM', confidence: 'HIGH', category: 'API2', cwe: 'CWE-613',
			method: 'POST', path: '/api/payments', routeId: 'payments', rate: 0.2,
			title: 'JWT without an expiry',
			detail: 'The bearer token carries no exp claim: once leaked, it stays valid for ever.',
			remediation: 'Issue tokens with a short exp and refresh them.',
			reference: 'https://owasp.org/API-Security/editions/2023/en/0xa2-broken-authentication/',
			evidence: { alg: 'RS256', exp: 'absent' } },
		{ scanner: 'security-headers', severity: 'MEDIUM', confidence: 'HIGH', category: 'API8', cwe: 'CWE-693',
			method: 'GET', path: '/api/catalog/products/{id}', routeId: 'catalog', rate: 1,
			title: 'Missing security headers',
			detail: 'The response sets neither Strict-Transport-Security nor X-Content-Type-Options.',
			remediation: 'Add the headers at the gateway with the SecureHeaders filter, or in the service.',
			reference: 'https://owasp.org/www-project-secure-headers/',
			evidence: { missing: 'Strict-Transport-Security, X-Content-Type-Options' } },
		{ scanner: 'verbose-error', severity: 'MEDIUM', confidence: 'MEDIUM', category: 'API8', cwe: 'CWE-209',
			method: 'PUT', path: '/service-b/items/{id}', routeId: 'service-b', rate: 0.01,
			title: 'Stack trace in an error response',
			detail: 'A 500 response carries a Java stack trace, naming classes, libraries and versions.',
			remediation: 'Answer errors with a problem detail and log the trace on the server only.',
			reference: 'https://owasp.org/API-Security/editions/2023/en/0xa8-security-misconfiguration/',
			evidence: { excerpt: 'java.lang.IllegalStateException: at com.example.items.ItemService.update(ItemService.java:88)' } },
		{ scanner: 'cookie-security', severity: 'LOW', confidence: 'HIGH', category: 'API8', cwe: 'CWE-614',
			method: 'GET', path: '/service-a/hello', routeId: 'service-a', rate: 0.5,
			title: 'Cookie without the Secure flag',
			detail: 'A Set-Cookie header lacks the Secure attribute: the cookie is sent over cleartext connections too.',
			remediation: 'Set Secure, HttpOnly and SameSite on every cookie.',
			reference: 'https://owasp.org/www-community/controls/SecureCookieAttribute',
			evidence: { cookie: 'SESSION', missing: 'Secure' } },
		{ scanner: 'info-disclosure', severity: 'LOW', confidence: 'HIGH', category: 'API8', cwe: 'CWE-200',
			method: 'GET', path: '/api/catalog/search', routeId: 'catalog', rate: 0.5,
			title: 'Server version disclosed',
			detail: 'The Server header names the software and its exact version.',
			remediation: 'Remove the Server header, or strip its version, with the RemoveResponseHeader filter.',
			reference: 'https://owasp.org/API-Security/editions/2023/en/0xa8-security-misconfiguration/',
			evidence: { Server: 'nginx/1.18.0' } },
		{ scanner: 'http-trace', severity: 'MEDIUM', confidence: 'HIGH', category: 'API8', cwe: 'CWE-693',
			method: 'TRACE', path: '/', routeId: null, rate: 0,
			title: 'TRACE method answered',
			detail: 'A TRACE request was answered with its own headers echoed back, cookies included.',
			remediation: 'Refuse TRACE at the gateway.',
			reference: 'https://owasp.org/www-project-web-security-testing-guide/',
			evidence: { status: '200' } }
	];

	var CATEGORIES = {
		API2: ['API2:2023', 'Broken Authentication', 'API2_BROKEN_AUTHENTICATION'],
		API3: ['API3:2023', 'Broken Object Property Level Authorization',
			'API3_BROKEN_OBJECT_PROPERTY_LEVEL_AUTHORIZATION'],
		API8: ['API8:2023', 'Security Misconfiguration', 'API8_SECURITY_MISCONFIGURATION']
	};

	FINDINGS.forEach(function (finding, index) {
		finding.count = 1 + index % 3;
		finding.firstSeen = now + index * 47000;
		finding.lastSeen = finding.firstSeen;
		finding.raised = 0;
	});

	/* The passing of time -------------------------------------------------------------- */

	function call(edge, at) {
		var target = route(edge.route);
		var request = pick(REQUESTS[edge.route]);
		var status = request[0] === 'POST' ? 201 : 200;
		var roll = Math.random();
		if (roll < edge.errors) {
			status = pick([500, 502, 503]);
		}
		else if (roll < edge.errors + edge.clientErrors) {
			status = pick([400, 401, 404, 404, 429]);
		}
		// Mostly around the route's own latency, with the odd slow call a maximum is for.
		var ms = target.latency * (0.5 + Math.random()) * (Math.random() < 0.02 ? 6 + Math.random() * 8 : 1);
		var figures = traffic[edge.route];
		figures.count += 1;
		figures.recent += 1;
		figures.totalMs += ms;
		figures.maxMs = Math.max(figures.maxMs, ms);
		edge.calls += 1;
		if (status >= 500) {
			figures.errorCount += 1;
			edge.errorCalls += 1;
		}
		else if (status >= 400) {
			figures.clientErrorCount += 1;
			edge.clientErrorCalls += 1;
		}
		return { edge: edge, request: request, status: status, at: at };
	}

	function record(exchange) {
		var caller = CALLERS[exchange.edge.from];
		var user = pick(caller.users);
		var path = exchange.request[1].replace('{n}', String(100 + Math.floor(Math.random() * 900)));
		if (exchange.request[1] === '/api/catalog/search') {
			path += '?q=' + pick(['shoes', 'lamp', 'desk', 'jacket']);
		}
		var traceId = hex(32);
		var attributes = {
			'request.method': exchange.request[0],
			'request.path': path,
			'request.ip': caller.ip + (10 + path.length % 40),
			'response.status': STATUS[exchange.status],
			'route.id': exchange.edge.route,
			'trace.id': traceId,
			'span.id': hex(16),
			'jwt.client.id': exchange.edge.from
		};
		if (user) {
			attributes['jwt.user.id'] = user;
		}
		audit.unshift({
			timestamp: new Date(exchange.at).toISOString(),
			method: exchange.request[0],
			path: path,
			status: STATUS[exchange.status],
			statusCode: exchange.status,
			user: user,
			ip: attributes['request.ip'],
			traceId: traceId,
			attributes: attributes
		});
		if (audit.length > AUDIT_CAPACITY) {
			audit.length = AUDIT_CAPACITY;
		}
	}

	/*
	 * Advances the gateway to `to`. Calls come in bursts — squared randomness, so most
	 * seconds are quiet and a few are busy — since a flow where every hop carries the same
	 * thing every time reads as a screensaver.
	 */
	function advance(to) {
		var seconds = Math.min((to - now) / 1000, 60);
		if (seconds <= 0) {
			return;
		}
		ROUTES.forEach(function (each) {
			traffic[each.id].recent = 0;
		});
		var audited = 0;
		EDGES.forEach(function (edge) {
			var calls = Math.round(edge.rate * seconds * Math.random() * Math.random() * 4);
			for (var i = 0; i < calls; i++) {
				var exchange = call(edge, now + Math.random() * (to - now));
				// The tail keeps a sample: every call would push the history out in a minute.
				if (Math.random() < 0.25 && audited < 40) {
					record(exchange);
					audited += 1;
				}
			}
		});
		audit.sort(function (left, right) {
			return left.timestamp < right.timestamp ? 1 : -1;
		});
		FINDINGS.forEach(function (finding) {
			if (!finding.routeId) {
				return;
			}
			finding.raised += traffic[finding.routeId].recent * finding.rate;
			var whole = Math.floor(finding.raised);
			if (whole > 0) {
				finding.count += whole;
				finding.raised -= whole;
				finding.lastSeen = to;
			}
		});
		now = to;
	}

	for (var minute = 30; minute > 0; minute--) {
		advance(Date.now() - minute * 60 * 1000 + 30000);
	}

	/* The endpoints -------------------------------------------------------------------- */

	function routeMetrics() {
		return {
			coverage: COVERAGE,
			metrics: ROUTES.map(function (each) {
				var figures = traffic[each.id];
				return {
					routeId: each.id, uri: each.uri, count: figures.count,
					avgMs: figures.count ? figures.totalMs / figures.count : 0,
					maxMs: figures.maxMs,
					clientErrorCount: figures.clientErrorCount,
					clientErrorRate: figures.count ? figures.clientErrorCount / figures.count : 0,
					errorCount: figures.errorCount,
					errorRate: figures.count ? figures.errorCount / figures.count : 0
				};
			})
		};
	}

	function serviceGraph() {
		var nodes = {};
		var edges = EDGES.map(function (edge) {
			return {
				from: edge.from, to: service(edge.route), routeId: edge.route, calls: edge.calls,
				clientErrors: edge.clientErrorCalls, errors: edge.errorCalls
			};
		}).sort(function (left, right) {
			return right.calls - left.calls;
		});
		edges.forEach(function (edge) {
			nodes[edge.to] = nodes[edge.to] || { id: edge.to, kind: 'SERVICE', calls: 0 };
			nodes[edge.to].calls += edge.calls;
		});
		edges.forEach(function (edge) {
			if (!nodes[edge.from]) {
				nodes[edge.from] = { id: edge.from, kind: 'CALLER', calls: 0 };
			}
			if (nodes[edge.from].kind === 'CALLER') {
				nodes[edge.from].calls += edge.calls;
			}
		});
		return { coverage: COVERAGE, nodes: Object.keys(nodes).map(function (id) {
			return nodes[id];
		}), edges: edges };
	}

	function auditEvents(query) {
		var status = query.get('status') || '';
		var text = (query.get('query') || '').toLowerCase();
		var limit = Number(query.get('limit')) || 100;
		return audit.filter(function (event) {
			if (status && String(event.statusCode).charAt(0) !== status.charAt(0)) {
				return false;
			}
			return !text || [event.path, event.user, event.ip, event.traceId].some(function (value) {
				return value && value.toLowerCase().indexOf(text) >= 0;
			});
		}).slice(0, limit);
	}

	/* A figure that wanders around a level, the same way on every read of one second. */
	function wander(level, spread, seed) {
		var t = Date.now() / 1000;
		return level + spread * (Math.sin(t / 7 + seed) + Math.sin(t / 3.1 + seed * 2) / 2) / 1.5;
	}

	function instanceMetrics() {
		var routesByAddress = {};
		ROUTES.forEach(function (each) {
			routesByAddress[service(each.id)] = [each.id];
		});
		return {
			coverage: COVERAGE,
			routesByAddress: routesByAddress,
			instances: INSTANCES.map(function (instance, index) {
				var load = ROUTES.reduce(function (sum, each) {
					return sum + traffic[each.id].recent;
				}, 0) * instance.share;
				var seconds = instance.bootedSecondsAgo + Math.round((Date.now() - started) / 1000);
				// A sawtooth: the heap fills, a collection empties it, it fills again.
				var heap = 120 + ((Date.now() / 1000 + index * 40) % 90) * 2.6;
				return {
					instanceId: instance.id,
					uri: instance.uri,
					uptimeSeconds: seconds,
					jvm: {
						heapUsedBytes: Math.round(heap * 1048576), heapMaxBytes: 536870912,
						nonHeapUsedBytes: Math.round(wander(94, 2, index) * 1048576),
						gcOverhead: Math.max(0, wander(0.004, 0.003, index)),
						gcPauseTotalMs: seconds / 120, gcPauseCount: Math.round(seconds / 1500),
						threadsLive: Math.round(wander(41, 3, index)), threadsPeak: 48, threadsDaemon: 36
					},
					system: {
						processCpuUsage: Math.min(1, 0.03 + load / 400 + Math.max(0, wander(0.02, 0.02, index))),
						systemCpuUsage: Math.min(1, 0.12 + load / 300 + Math.max(0, wander(0.05, 0.04, index + 1))),
						loadAverage1m: Math.max(0, wander(1.2, 0.5, index)), cpuCount: 4,
						openFiles: Math.round(wander(212, 12, index)), maxFiles: 1048576
					},
					netty: { eventLoopPendingTasks: Math.random() < 0.1 ? 1 : 0, eventLoops: 8 },
					pools: ROUTES.map(function (each) {
						var busy = traffic[each.id].recent * instance.share * each.latency / 1000;
						var active = Math.round(busy * (1 + Math.random()));
						return {
							name: 'proxy', remoteAddress: service(each.id), active: active,
							idle: Math.max(0, 6 - active), pending: active > 8 ? active - 8 : 0,
							max: 500, maxPending: 1000,
							pendingTimeAvgMs: active > 8 ? 2 + Math.random() * 6 : Math.random()
						};
					}),
					instrumentation: { connectionPool: true, httpClient: true }
				};
			})
		};
	}

	function findingView(finding) {
		var category = CATEGORIES[finding.category];
		return {
			ruleId: 'PS-' + finding.scanner, lastSeen: new Date(finding.lastSeen).toISOString(),
			firstSeen: new Date(finding.firstSeen).toISOString(), count: finding.count,
			severity: finding.severity, confidence: finding.confidence,
			categoryCode: category[0], categoryTitle: category[1], cwe: finding.cwe,
			scanner: finding.scanner, method: finding.method, path: finding.path, title: finding.title,
			detail: finding.detail, remediation: finding.remediation, reference: finding.reference,
			routeId: finding.routeId, evidence: finding.evidence
		};
	}

	var SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];

	function findings(query) {
		var severity = query.get('severity') || '';
		var text = (query.get('query') || '').toLowerCase();
		return FINDINGS.filter(function (finding) {
			return (!severity || finding.severity === severity) && (!text
				|| [finding.title, finding.path, finding.scanner, finding.routeId || ''].join(' ')
					.toLowerCase().indexOf(text) >= 0);
		}).sort(function (left, right) {
			return SEVERITIES.indexOf(left.severity) - SEVERITIES.indexOf(right.severity)
				|| right.lastSeen - left.lastSeen;
		}).slice(0, Number(query.get('limit')) || 200).map(findingView);
	}

	function summary() {
		var bySeverity = {};
		var byCategory = {};
		FINDINGS.forEach(function (finding) {
			bySeverity[finding.severity] = (bySeverity[finding.severity] || 0) + 1;
			var key = CATEGORIES[finding.category][2];
			byCategory[key] = (byCategory[key] || 0) + 1;
		});
		return { total: FINDINGS.length, bySeverity: bySeverity, byCategory: byCategory };
	}

	/* What the scanners can see, as the passive analyser declares it. */
	var PASSIVE_COVERAGE = [
		['API1:2023', 'Broken Object Level Authorization', 'NOT_PASSIVE',
			'Needs replaying a request as another identity; only active scanning can prove it.'],
		['API2:2023', 'Broken Authentication', 'GOOD', 'jwt-weakness, basic-auth, broken-auth, token-in-response.'],
		['API3:2023', 'Broken Object Property Level Authorization', 'PARTIAL',
			'excessive-data-exposure, pii-in-url; mass assignment needs active probing.'],
		['API4:2023', 'Unrestricted Resource Consumption', 'NOT_PASSIVE',
			'Needs generating load; the passive analyser sends nothing.'],
		['API5:2023', 'Broken Function Level Authorization', 'PARTIAL',
			'insecure-methods, http-trace; proving BFLA needs active probing.'],
		['API6:2023', 'Unrestricted Access to Sensitive Business Flows', 'NOT_PASSIVE',
			'Needs business-logic knowledge and abuse-case testing.'],
		['API7:2023', 'Server Side Request Forgery', 'NOT_PASSIVE', 'Needs an out-of-band probe (OAST).'],
		['API8:2023', 'Security Misconfiguration', 'GOOD',
			'security-headers, cors, cookie-security, cleartext, info-disclosure, verbose-error, csp-weakness, '
			+ 'hsts-weakness, referrer-policy, cache-control, http-trace, parameter-pollution, open-redirect, '
			+ 'insecure-methods, csrf-cookie, graphql-introspection, directory-listing.'],
		['API9:2023', 'Improper Inventory Management', 'PARTIAL',
			'info-disclosure; a full inventory needs the OpenAPI contract.'],
		['API10:2023', 'Unsafe Consumption of APIs', 'NOT_PASSIVE',
			'Concerns the service\'s own upstream calls, not visible at the gateway edge.']
	].map(function (row) {
		return { code: row[0], title: row[1], status: row[2], note: row[3] };
	});

	/* The score of every route, worst first: 100 less a penalty per severity that halves. */
	function routeScores() {
		var WEIGHTS = { CRITICAL: 30, HIGH: 15, MEDIUM: 7, LOW: 3, INFO: 1 };
		return ROUTES.map(function (each) {
			var raised = FINDINGS.filter(function (finding) {
				return finding.routeId === each.id;
			});
			var count = function (severity) {
				return raised.filter(function (finding) {
					return finding.severity === severity;
				}).length;
			};
			var penalty = SEVERITIES.reduce(function (sum, severity) {
				var n = count(severity);
				return sum + (n ? 2 * WEIGHTS[severity] * (1 - Math.pow(2, -n)) : 0);
			}, 0);
			var score = Math.max(0, 100 - Math.round(penalty));
			return {
				routeId: each.id, path: each.path, score: score,
				grade: score >= 80 ? 'GOOD' : score >= 60 ? 'FAIR' : score >= 40 ? 'POOR' : 'BAD',
				findings: raised.length, critical: count('CRITICAL'), high: count('HIGH'),
				medium: count('MEDIUM'), low: count('LOW') + count('INFO')
			};
		}).sort(function (left, right) {
			return left.score - right.score || (left.routeId < right.routeId ? -1 : 1);
		});
	}

	/* Introspection ---------------------------------------------------------------------- */

	var LEVELS = ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'];
	var LOGGER_NAMES = ['ROOT', 'ch', 'ch.nexsol', 'ch.nexsol.gateway', 'ch.nexsol.gateway.audit',
		'ch.nexsol.gateway.metrics', 'ch.nexsol.gateway.pentest', 'ch.nexsol.gateway.routes',
		'ch.nexsol.gateway.ui', 'io', 'io.netty', 'org', 'org.springframework', 'org.springframework.boot',
		'org.springframework.cloud', 'org.springframework.cloud.gateway',
		'org.springframework.cloud.gateway.filter', 'org.springframework.cloud.gateway.route',
		'org.springframework.security', 'reactor', 'reactor.netty', 'reactor.netty.http.client'];
	var DECLARED = { 'ROOT': 'INFO', 'ch.nexsol.gateway': 'INFO', 'io.netty': 'WARN' };

	/* What each instance was told at run time: the second one is debugging its HTTP client. */
	var configured = {
		'gateway-1': Object.assign({}, DECLARED),
		'gateway-2': Object.assign({ 'reactor.netty.http.client': 'DEBUG' }, DECLARED)
	};

	function loggersOf(instanceId) {
		var levels = configured[instanceId];
		var loggers = {};
		LOGGER_NAMES.forEach(function (name) {
			var effective = null;
			for (var at = name; !effective; at = at.indexOf('.') > 0 ? at.slice(0, at.lastIndexOf('.')) : 'ROOT') {
				effective = levels[at] || (at === 'ROOT' ? 'INFO' : null);
			}
			loggers[name] = { configuredLevel: levels[name] || null, effectiveLevel: effective };
		});
		return loggers;
	}

	/* The fleet as one: a logger the instances disagree on claims no level and says so. */
	function loggers(instanceId) {
		if (instanceId && instanceId !== '*') {
			return { levels: LEVELS, loggers: loggersOf(instanceId), _instance: instanceId, _baseline: DECLARED };
		}
		var answers = INSTANCES.map(function (instance) {
			return loggersOf(instance.id);
		});
		var merged = {};
		LOGGER_NAMES.forEach(function (name) {
			var first = answers[0][name];
			var logger = { configuredLevel: first.configuredLevel, effectiveLevel: first.effectiveLevel };
			answers.slice(1).forEach(function (other) {
				if (other[name].configuredLevel !== first.configuredLevel) {
					logger.mixed = true;
				}
				if (other[name].effectiveLevel !== first.effectiveLevel) {
					logger.effectiveLevel = null;
					logger.mixed = true;
				}
			});
			if (logger.mixed) {
				logger.configuredLevel = null;
			}
			merged[name] = logger;
		});
		return { levels: LEVELS, loggers: merged, _baseline: DECLARED, _merged: INSTANCES.map(function (instance) {
			return instance.id;
		}) };
	}

	function setLevel(name, instanceId, level) {
		INSTANCES.forEach(function (instance) {
			if (!instanceId || instanceId === '*' || instanceId === instance.id) {
				if (level) {
					configured[instance.id][name] = level;
				}
				else {
					delete configured[instance.id][name];
				}
			}
		});
	}

	function context(content) {
		return { contexts: { gateway: content } };
	}

	var INSIGHTS = {
		configuration: function () {
			var bean = function (prefix, type, properties) {
				return [prefix + '-' + type, { prefix: prefix, properties: properties }];
			};
			var beans = {};
			[
				bean('spring.cloud.gateway.server.webflux', 'org.springframework.cloud.gateway.config.GatewayProperties',
					{ 'fail-on-route-definition-error': true, 'default-filters': [] }),
				bean('spring.cloud.gateway.server.webflux.httpclient',
					'org.springframework.cloud.gateway.config.HttpClientProperties',
					{ 'connect-timeout': 2000, 'response-timeout': '10s', 'pool.type': 'ELASTIC',
						'pool.max-idle-time': '15s', 'pool.metrics': true }),
				bean('spring.cloud.gateway.server.webflux.metrics', 'ch.nexsol.gateway.metrics.MetricsProperties',
					{ 'enabled': true, 'provider': 'redis', 'instance.enabled': true,
						'excluded-routes': ['/v3/api-docs/**'] }),
				bean('spring.cloud.gateway.server.webflux.audit', 'ch.nexsol.gateway.audit.AuditProperties',
					{ 'enabled': true, 'provider': 'redis', 'groups.jwt': true, 'groups.request': true,
						'groups.response': true, 'groups.trace': true, 'groups.route': true, 'groups.validation': true }),
				bean('spring.cloud.gateway.server.webflux.pentest.passive',
					'ch.nexsol.gateway.pentest.passive.PassiveScanProperties',
					{ 'enabled': true, 'routed-only': true, 'max-findings': 500, 'queue-capacity': 1024,
						'exclude-paths': ['/passive-scan/**'], 'body.enabled': false, 'body.max-bytes': 65536,
						'jwt.clock-skew': '60s' }),
				bean('management.endpoints.web',
					'org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties',
					{ 'base-path': '/actuator', 'exposure.include': ['health', 'info', 'prometheus', 'loggers', 'env',
						'configprops', 'beans', 'conditions', 'mappings'] }),
				bean('spring.data.redis', 'org.springframework.boot.data.redis.autoconfigure.DataRedisProperties',
					{ 'host': 'redis', 'port': 6379, 'database': 0, 'timeout': '2s' })
			].forEach(function (entry) {
				beans[entry[0]] = entry[1];
			});
			return context({ beans: beans });
		},
		'profile-diff': function () {
			var source = function (name, properties) {
				var wrapped = {};
				Object.keys(properties).forEach(function (key) {
					wrapped[key] = { value: properties[key] };
				});
				return { name: name, properties: wrapped };
			};
			var resource = function (file) {
				return 'Config resource \'class path resource [' + file + ']\' via location \'optional:classpath:/\'';
			};
			return {
				activeProfiles: ['redis', 'eureka'],
				propertySources: [
					source('server.ports', { 'local.server.port': 8181 }),
					source('systemEnvironment', { 'SPRING_PROFILES_ACTIVE': 'redis,eureka',
						'SPRING_DATA_REDIS_HOST': 'redis', 'JAVA_TOOL_OPTIONS': '-XX:MaxRAMPercentage=75' }),
					source(resource('application-redis.yml'), { 'spring.data.redis.host': 'redis',
						'spring.cloud.gateway.server.webflux.metrics.provider': 'redis',
						'spring.cloud.gateway.server.webflux.audit.provider': 'redis' }),
					source(resource('application-eureka.yml'), {
						'eureka.client.service-url.defaultZone': 'http://eureka:8761/eureka',
						'spring.cloud.gateway.server.webflux.discovery.locator.enabled': true }),
					source(resource('application.yml'), { 'server.port': 8181, 'spring.application.name': 'gateway',
						'spring.data.redis.host': 'localhost',
						'spring.cloud.gateway.server.webflux.audit.provider': 'r2dbc',
						'spring.cloud.gateway.server.webflux.pentest.passive.enabled': true,
						'management.endpoints.web.exposure.include': '*' })
				]
			};
		},
		beans: function () {
			var beans = {};
			[
				['gatewayProperties', 'org.springframework.cloud.gateway.config.GatewayProperties', []],
				['httpClientProperties', 'org.springframework.cloud.gateway.config.HttpClientProperties', []],
				['routeDefinitionLocator', 'org.springframework.cloud.gateway.route.CompositeRouteDefinitionLocator', []],
				['cachedCompositeRouteLocator', 'org.springframework.cloud.gateway.route.CachingRouteLocator', []],
				['redisConnectionFactory', 'org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory', []],
				['reactiveRedisTemplate', 'org.springframework.data.redis.core.ReactiveRedisTemplate',
					['redisConnectionFactory']],
				['prometheusMeterRegistry', 'io.micrometer.prometheusmetrics.PrometheusMeterRegistry', []],
				['gatewayUiMenu', 'ch.nexsol.gateway.ui.nav.GatewayUiMenu', []],
				['auditTailBuffer', 'ch.nexsol.gateway.ui.audit.AuditTailBuffer', []],
				['actuatorInstances', 'ch.nexsol.gateway.ui.insights.ActuatorInstances', []],
				['routeScoreService', 'ch.nexsol.gateway.pentest.passive.score.RouteScoreService',
					['routeDefinitionLocator']]
			].forEach(function (bean) {
				beans[bean[0]] = { aliases: [], scope: 'singleton', type: bean[1], dependencies: bean[2] };
			});
			return context({ beans: beans });
		},
		conditions: function () {
			var on = function (type, message) {
				return [{ condition: type, message: message }];
			};
			return context({
				positiveMatches: {
					'RedisAuditAutoConfiguration': on('OnPropertyCondition',
						'@ConditionalOnProperty (spring.cloud.gateway.server.webflux.audit.provider=redis) matched'),
					'RedisMetricsAutoConfiguration': on('OnPropertyCondition',
						'@ConditionalOnProperty (spring.cloud.gateway.server.webflux.metrics.enabled) matched'),
					'PrometheusMetricsAutoConfiguration': on('OnClassCondition',
						'@ConditionalOnClass found required class \'org.springframework.web.reactive.function.client.WebClient\''),
					'DataRedisReactiveAutoConfiguration': on('OnClassCondition',
						'@ConditionalOnClass found required classes \'org.springframework.data.redis.connection.ReactiveRedisConnectionFactory\', \'org.springframework.data.redis.core.ReactiveRedisTemplate\', \'reactor.core.publisher.Flux\'')
				},
				negativeMatches: {
					'KafkaAuditAutoConfiguration': { notMatched: on('OnClassCondition',
						'@ConditionalOnClass did not find required class \'org.springframework.kafka.core.KafkaTemplate\''), matched: [] },
					'R2dbcAuditAutoConfiguration': { notMatched: on('OnPropertyCondition',
						'@ConditionalOnProperty (spring.cloud.gateway.server.webflux.audit.provider=r2dbc) found different value in property \'spring.cloud.gateway.server.webflux.audit.provider\''),
					matched: [] }
				}
			});
		},
		mappings: function () {
			var handler = function (predicate, target) {
				return { predicate: predicate, handler: target };
			};
			return context({ mappings: { dispatcherHandlers: { webHandler: [
				handler('{GET [/ui/metrics/data]}', 'ch.nexsol.gateway.ui.metrics.RouteMetricsController#data'),
				handler('{GET [/ui/metrics/instances/data]}', 'ch.nexsol.gateway.ui.metrics.InstanceMetricsController#data'),
				handler('{GET [/ui/service-graph/data]}', 'ch.nexsol.gateway.ui.servicegraph.ServiceGraphController#data'),
				handler('{GET [/ui/service-graph/flow]}', 'ch.nexsol.gateway.ui.servicegraph.ServiceGraphController#flow'),
				handler('{GET [/ui/audit/events]}', 'ch.nexsol.gateway.ui.audit.AuditTailController#events'),
				handler('{GET [/ui/passive-scan/findings]}', 'ch.nexsol.gateway.ui.passivescan.PassiveScanViewController#findings'),
				handler('{GET [/ui/passive-scan/routes/data]}', 'ch.nexsol.gateway.ui.passivescan.PassiveScanViewController#routesData'),
				handler('{GET [/ui/insights/{view}/data]}', 'ch.nexsol.gateway.ui.insights.InsightsController#data'),
				handler('{POST [/ui/insights/loggers/{name}]}', 'ch.nexsol.gateway.ui.insights.InsightsController#setLevel'),
				handler('{GET [/actuator/health]}', 'Actuator web endpoint \'health\''),
				handler('{GET [/actuator/prometheus]}', 'Actuator web endpoint \'prometheus\''),
				handler('{GET [/actuator/loggers]}', 'Actuator web endpoint \'loggers\'')
			] } } });
		}
	};

	/* OpenAPI ---------------------------------------------------------------------------- */

	var CONTRACTS = {
		orders: [['get', '/api/orders', 'List the orders'], ['post', '/api/orders', 'Place an order'],
			['get', '/api/orders/{id}', 'Read an order'], ['delete', '/api/orders/{id}', 'Cancel an order']],
		payments: [['post', '/api/payments', 'Pay for an order'], ['get', '/api/payments/{id}', 'Read a payment']],
		catalog: [['get', '/api/catalog/products/{id}', 'Read a product'],
			['get', '/api/catalog/search', 'Search the catalog']]
	};

	function contract(name) {
		var paths = {};
		CONTRACTS[name].forEach(function (operation) {
			var parameters = operation[1].indexOf('{id}') >= 0
				? [{ name: 'id', 'in': 'path', required: true, schema: { type: 'integer' } }] : [];
			paths[operation[1]] = paths[operation[1]] || {};
			paths[operation[1]][operation[0]] = {
				tags: [name], summary: operation[2], operationId: operation[0] + operation[1].replace(/\W+/g, '-'),
				parameters: parameters,
				responses: { '200': { description: 'OK', content: { 'application/json': { schema: { type: 'object' } } } } }
			};
		});
		return {
			openapi: '3.0.1',
			info: { title: name.charAt(0).toUpperCase() + name.slice(1) + ' API', version: '1.4.0',
				description: 'An invented contract: nothing answers behind it.' },
			servers: [{ url: '/' }],
			paths: paths
		};
	}

	/* The stub --------------------------------------------------------------------------- */

	function json(body, status) {
		return new Response(JSON.stringify(body), {
			status: status || 200, headers: { 'Content-Type': 'application/json' }
		});
	}

	function answer(method, path, query, body) {
		var insight = path.match(/^\/ui\/insights\/([\w-]+)\/data$/);
		var level = path.match(/^\/ui\/insights\/loggers\/([^/]+)$/);
		var contractName = path.match(/^\/v3\/api-docs\/(\w+)$/);
		switch (true) {
			case path === '/ui/metrics/data':
				return json(routeMetrics());
			case path === '/ui/metrics/instances/data':
				return json(instanceMetrics());
			case path === '/ui/service-graph/data':
				return json(serviceGraph());
			case path === '/ui/audit/events':
				return json(auditEvents(query));
			case path === '/ui/passive-scan/coverage':
				return json(PASSIVE_COVERAGE);
			case path === '/ui/passive-scan/findings':
				return json(findings(query));
			case path === '/ui/passive-scan/summary':
				return json(summary());
			case path === '/ui/passive-scan/routes/data':
				return json(routeScores());
			case method === 'POST' && !!level:
				setLevel(decodeURIComponent(level[1]), query.get('instance'), JSON.parse(body || '{}').configuredLevel);
				return new Response(null, { status: 204 });
			case !!insight && insight[1] === 'loggers':
				return json(loggers(query.get('instance')));
			case !!insight && !!INSIGHTS[insight[1]]:
				return json(Object.assign(INSIGHTS[insight[1]](), { _instance: query.get('instance') || 'gateway-1' }));
			case path === '/v3/api-docs/swagger-config':
				return json({ configUrl: '/v3/api-docs/swagger-config', urls: Object.keys(CONTRACTS).map(function (name) {
					return { name: name, url: '/v3/api-docs/' + name };
				}) });
			case !!contractName && !!CONTRACTS[contractName[1]]:
				return json(contract(contractName[1]));
			default:
				return json({ status: 404, detail: 'No gateway answers this demo: ' + path }, 404);
		}
	}

	window.fetch = function (input, init) {
		var url = new URL(typeof input === 'string' ? input : input.url, window.location.href);
		var at = url.pathname.search(/\/(ui|v3)\//);
		var path = at >= 0 ? url.pathname.slice(at) : url.pathname;
		advance(Date.now());
		return Promise.resolve(answer((init && init.method) || 'GET', path, url.searchParams, init && init.body));
	};
})();
