/*
 * Instances view. One card per gateway instance: the JVM figures on top, the connection
 * pools towards the downstream services below, sorted by how full they are.
 *
 * The pools come first among equals on purpose. A pool filling up towards a slow backend
 * takes down every route pointing at that address while the JVM itself still looks
 * healthy, so it is the one thing here that no per-route figure can reveal.
 *
 * Plain markup and CSS rather than a charting library: these are bars, and the view
 * already costs a poll.
 */
(function () {
	var container = document.getElementById('gi-instances');
	if (!container) {
		return;
	}

	var coverageEl = document.getElementById('gi-coverage');
	var emptyEl = document.getElementById('gi-empty');
	var autoEl = document.getElementById('gi-auto');
	var refreshEl = document.getElementById('gi-refresh');
	var url = container.getAttribute('data-url');
	var timer = null;

	// The routes behind each downstream address, resolved once per snapshot rather than
	// per row: the same address means the same routes on every instance.
	var routesByAddress = {};

	/*
	 * The instances whose pool table the reader unfolded: a table is folded away until it is
	 * asked for, so what is kept is the exception rather than the rule.
	 *
	 * Kept across a refresh — the view redraws every five seconds and would otherwise fold
	 * back what the reader just opened — and written down like the side menu and the theme.
	 */
	var EXPANDED_KEY = 'gw-instances-expanded';
	var expanded = readExpanded();

	// The last payload rendered, redrawn on its own when a table is folded or unfolded.
	var snapshot = null;

	function readExpanded() {
		try {
			return (localStorage.getItem(EXPANDED_KEY) || '').split(',').filter(Boolean);
		}
		catch (ignored) {
			return [];
		}
	}

	function writeExpanded() {
		try {
			localStorage.setItem(EXPANDED_KEY, expanded.join(','));
		}
		catch (ignored) {
			// A browser refusing storage keeps the choice for this page and no further.
		}
	}

	function isExpanded(instanceId) {
		return expanded.indexOf(instanceId) >= 0;
	}

	// The property that publishes the connection pool counters. Named in full in the
	// view, because a reader looking at an empty pool section needs the fix, not the
	// observation that something is missing.
	var POOL_PROPERTY = 'spring.cloud.gateway.server.webflux.httpclient.pool.metrics';
	var CLIENT_PROPERTY = 'spring.cloud.gateway.server.webflux.metrics.instance.instrument-http-client';

	function escape(value) {
		if (value === null || value === undefined) {
			return '';
		}
		return String(value).replace(/[&<>"']/g, function (character) {
			return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
		});
	}

	/** A figure the JVM does not publish is reported as -1 and shown as a dash. */
	function missing(value) {
		return value === null || value === undefined || value < 0;
	}

	function bytes(value) {
		if (missing(value)) {
			return '&mdash;';
		}
		var units = ['B', 'KB', 'MB', 'GB', 'TB'];
		var index = 0;
		var scaled = value;
		while (scaled >= 1024 && index < units.length - 1) {
			scaled /= 1024;
			index++;
		}
		return (scaled >= 100 ? Math.round(scaled) : Math.round(scaled * 10) / 10) + ' ' + units[index];
	}

	function percent(ratio) {
		if (ratio === null || ratio === undefined || isNaN(ratio)) {
			return '&mdash;';
		}
		return Math.round(ratio * 1000) / 10 + '%';
	}

	function count(value) {
		return missing(value) ? '&mdash;' : Math.round(value);
	}

	function uptime(value) {
		var total = Math.floor(value || 0);
		var days = Math.floor(total / 86400);
		var hours = Math.floor((total % 86400) / 3600);
		var minutes = Math.floor((total % 3600) / 60);
		if (days > 0) {
			return 'up ' + days + 'd ' + hours + 'h';
		}
		if (hours > 0) {
			return 'up ' + hours + 'h ' + minutes + 'm';
		}
		return 'up ' + minutes + 'm';
	}

	/** A ratio, or null when the ceiling is unknown and a share cannot be computed. */
	function ratio(value, ceiling) {
		return (ceiling > 0) ? value / ceiling : null;
	}

	function barClass(share) {
		if (share === null) {
			return 'bg-secondary';
		}
		if (share >= 0.9) {
			return 'bg-danger';
		}
		if (share >= 0.7) {
			return 'bg-warning';
		}
		return 'bg-success';
	}

	function bar(share) {
		var width = (share === null) ? 0 : Math.min(100, Math.round(share * 100));
		return '<div class="progress" style="height: 6px" role="progressbar">'
			+ '<div class="progress-bar ' + barClass(share) + '" style="width: ' + width + '%"></div></div>';
	}

	function figure(label, value, share) {
		return '<div class="col-6 col-md-4 col-xl-2">'
			+ '<div class="text-secondary small">' + label + '</div>'
			+ '<div class="fw-semibold">' + value + '</div>'
			+ (share === undefined ? '' : bar(share))
			+ '</div>';
	}

	function jvmFigures(instance) {
		var jvm = instance.jvm;
		var system = instance.system;
		var heapShare = ratio(jvm.heapUsedBytes, jvm.heapMaxBytes);
		var heap = missing(jvm.heapUsedBytes) ? '&mdash;'
			: bytes(jvm.heapUsedBytes) + ' / ' + bytes(jvm.heapMaxBytes);
		var files = missing(system.openFiles) ? '&mdash;'
			: count(system.openFiles) + ' / ' + count(system.maxFiles);
		return '<div class="row g-3">'
			+ figure('Heap', heap, heapShare)
			+ figure('Process CPU', percent(system.processCpuUsage), system.processCpuUsage)
			+ figure('Threads', count(jvm.threadsLive) + ' <span class="text-secondary fw-normal small">peak '
				+ count(jvm.threadsPeak) + '</span>')
			+ figure('GC overhead', percent(jvm.gcOverhead), jvm.gcOverhead)
			+ figure('Non-heap', bytes(jvm.nonHeapUsedBytes))
			+ figure('Open files', files, ratio(system.openFiles, system.maxFiles))
			+ '</div>';
	}

	// How many route ids a cell spells out before counting the rest. A contract turned
	// into one route per operation puts twenty of them on a single address, and the
	// column has to stay a column.
	var ROUTES_SHOWN = 2;

	/**
	 * The routes behind a downstream address. An address in no route table and no
	 * registry keeps a dash: the downstream was called, but nothing here can say on whose
	 * behalf, and naming it after a route it may not serve would be worse than silence.
	 */
	function routeCell(address) {
		var routes = routesByAddress[address] || [];
		if (!routes.length) {
			return '&mdash;';
		}
		var shown = routes.slice(0, ROUTES_SHOWN).map(escape).join(', ');
		return (routes.length > ROUTES_SHOWN)
			? shown + ' <span class="text-secondary fw-normal">+' + (routes.length - ROUTES_SHOWN) + '</span>'
			: shown;
	}

	function poolRow(pool) {
		var saturation = ratio(pool.active, pool.max);
		var queue = (pool.maxPending > 0) ? count(pool.pending) + ' / ' + count(pool.maxPending)
			: count(pool.pending);
		return '<tr>'
			+ '<td class="fw-semibold">' + routeCell(pool.remoteAddress) + '</td>'
			+ '<td class="text-nowrap">' + escape(pool.name) + ' <span class="text-secondary">&rarr;</span> '
			+ escape(pool.remoteAddress) + '</td>'
			+ '<td style="min-width: 8rem">' + bar(saturation) + '</td>'
			+ '<td class="text-end text-nowrap">' + count(pool.active) + ' / ' + count(pool.max) + '</td>'
			+ '<td class="text-end">' + count(pool.idle) + '</td>'
			+ '<td class="text-end text-nowrap">' + queue + '</td>'
			+ '<td class="text-end text-nowrap">' + Math.round(pool.pendingTimeAvgMs) + ' ms</td>'
			+ '</tr>';
	}

	function poolSection(instance) {
		if (!instance.instrumentation || !instance.instrumentation.connectionPool) {
			// An empty list would read as "no downstream called yet", which calls for
			// waiting rather than for a configuration change.
			return '<p class="text-secondary small mb-0">Connection pool counters are off. Set <code>'
				+ POOL_PROPERTY + '=true</code> to collect them.</p>';
		}
		if (!instance.pools || instance.pools.length === 0) {
			return '<p class="text-secondary small mb-0">No downstream called yet.</p>';
		}
		// Fullest pool first: the row that matters is the one at the top.
		var pools = instance.pools.slice().sort(function (left, right) {
			return (ratio(right.active, right.max) || 0) - (ratio(left.active, left.max) || 0);
		});
		// The count is stated on the fold: folded away, it is all that is left of the table,
		// and a reader has to know whether what is behind it is three rows or a hundred.
		var folded = !isExpanded(instance.instanceId);
		return '<button type="button" class="btn btn-link btn-sm p-0 mb-2 text-secondary small'
			+ ' text-decoration-none gw-pools-toggle" data-gi-toggle="' + escape(instance.instanceId) + '"'
			+ ' aria-expanded="' + !folded + '">' + (folded ? '&#9656;' : '&#9662;') + ' '
			+ pools.length + (pools.length === 1 ? ' pool' : ' pools') + ', fullest first.</button>'
			+ '<div class="table-responsive gw-pools"' + (folded ? ' style="display: none"' : '') + '>'
			+ '<table class="table table-sm align-middle mb-0">'
			+ '<thead><tr class="text-secondary small">'
			+ '<th>Route</th><th>Pool</th><th>Saturation</th><th class="text-end">Active / max</th>'
			+ '<th class="text-end">Idle</th><th class="text-end">Pending</th><th class="text-end">Avg wait</th>'
			+ '</tr></thead><tbody>'
			+ pools.map(poolRow).join('')
			+ '</tbody></table></div>';
	}

	function eventLoopLine(instance) {
		if (!instance.instrumentation || !instance.instrumentation.httpClient) {
			return '<p class="text-secondary small mb-0 mt-3">Event loop counters are off. Set <code>'
				+ CLIENT_PROPERTY + '=true</code> to collect them.</p>';
		}
		var netty = instance.netty;
		return '<p class="text-secondary small mb-0 mt-3">Event loop &mdash; ' + count(netty.eventLoopPendingTasks)
			+ ' pending task(s) across ' + count(netty.eventLoops) + ' loop(s).</p>';
	}

	function card(instance) {
		var target = instance.uri ? '<span class="text-secondary small">' + escape(instance.uri) + '</span>' : '';
		return '<div class="card border-0 shadow-sm mb-3"><div class="card-body">'
			+ '<div class="d-flex flex-wrap gap-2 align-items-baseline justify-content-between mb-3">'
			+ '<h2 class="h6 fw-semibold mb-0">' + escape(instance.instanceId) + ' ' + target + '</h2>'
			+ '<span class="text-secondary small">' + uptime(instance.uptimeSeconds) + '</span>'
			+ '</div>'
			+ jvmFigures(instance)
			+ '<hr class="my-3">'
			+ poolSection(instance)
			+ eventLoopLine(instance)
			+ '</div></div>';
	}

	function render(payload) {
		snapshot = payload;
		coverageEl.textContent = payload.coverage || '';
		routesByAddress = payload.routesByAddress || {};
		var instances = payload.instances || [];
		emptyEl.style.display = instances.length ? 'none' : '';
		container.innerHTML = instances.map(card).join('');
	}

	/*
	 * Folding is bound to the container rather than to the buttons: the cards are replaced
	 * wholesale on every refresh, and a listener put on a button would go with it.
	 */
	container.addEventListener('click', function (event) {
		var toggle = event.target.closest('[data-gi-toggle]');
		if (!toggle) {
			return;
		}
		var instanceId = toggle.getAttribute('data-gi-toggle');
		var index = expanded.indexOf(instanceId);
		if (index >= 0) {
			expanded.splice(index, 1);
		}
		else {
			expanded.push(instanceId);
		}
		writeExpanded();
		if (snapshot) {
			render(snapshot);
		}
	});

	function load() {
		fetch(url, { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.ok ? response.json() : Promise.reject(response.status);
			})
			.then(render)
			.catch(function () {
				coverageEl.textContent = 'Could not read the instance figures.';
			});
	}

	function schedule() {
		if (timer) {
			clearInterval(timer);
			timer = null;
		}
		if (autoEl && autoEl.checked) {
			timer = setInterval(load, 5000);
		}
	}

	if (window.gatewayUi) {
		window.gatewayUi.remember(autoEl);
	}
	if (autoEl) {
		autoEl.addEventListener('change', schedule);
	}
	if (refreshEl) {
		refreshEl.addEventListener('click', load);
	}
	load();
	schedule();
})();
