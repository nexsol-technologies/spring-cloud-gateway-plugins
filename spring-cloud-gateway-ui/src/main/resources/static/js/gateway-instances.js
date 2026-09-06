/*
 * Runtime view: one table, one row per gateway instance.
 *
 * A table rather than a card each, and no summary above it, because behind a load balancer
 * every instance carries traffic and the question is always comparative — which of them is
 * closest to a ceiling. A figure aggregated over the fleet answers that for nobody: the
 * highest heap and the highest processor reading rarely belong to the same instance, so a
 * row of maxima describes an instance that does not exist. Down a column, the same figure
 * on every instance is one glance, and it stays one glance at twenty of them.
 *
 * The pools of an instance are folded away behind its row. They come first among equals on
 * purpose: a pool filling up towards a slow backend takes down every route pointing at that
 * address while the JVM itself still looks healthy, so it is the one thing here that no
 * per-route figure can reveal.
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
	var ageEl = document.getElementById('gi-age');
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

	// The last payload rendered, redrawn on its own when a row is folded or unfolded.
	var snapshot = null;

	// When the payload on screen was read, for the age shown in the band.
	var readAt = null;

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
			return '—';
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
			return '—';
		}
		return Math.round(ratio * 1000) / 10 + '%';
	}

	function count(value) {
		return missing(value) ? '—' : Math.round(value);
	}

	/** How long an instance has been running, for a column already headed "Up". */
	function since(value) {
		var total = Math.floor(value || 0);
		var days = Math.floor(total / 86400);
		var hours = Math.floor((total % 86400) / 3600);
		var minutes = Math.floor((total % 3600) / 60);
		if (days > 0) {
			return days + 'd ' + hours + 'h';
		}
		if (hours > 0) {
			return hours + 'h ' + minutes + 'm';
		}
		return minutes + 'm';
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

	/* The shares of a ceiling ------------------------------------------------------- */

	/*
	 * Each of these answers "how close to its ceiling", or null when the instance does not
	 * publish enough to say. Null rather than zero: a JVM that reports no file descriptor
	 * figure has not got zero file descriptors open, and a bar drawn from a zero would
	 * report a healthy instance.
	 */

	function heapShare(instance) {
		return missing(instance.jvm.heapUsedBytes) ? null
			: ratio(instance.jvm.heapUsedBytes, instance.jvm.heapMaxBytes);
	}

	function cpuShare(instance) {
		return missing(instance.system.processCpuUsage) ? null : instance.system.processCpuUsage;
	}

	function gcShare(instance) {
		return missing(instance.jvm.gcOverhead) ? null : instance.jvm.gcOverhead;
	}

	function filesShare(instance) {
		return missing(instance.system.openFiles) ? null
			: ratio(instance.system.openFiles, instance.system.maxFiles);
	}

	function poolShare(instance) {
		var highest = null;
		(instance.pools || []).forEach(function (pool) {
			var share = ratio(pool.active, pool.max);
			if (share !== null && (highest === null || share > highest)) {
				highest = share;
			}
		});
		return highest;
	}

	/**
	 * The figure of an instance closest to its ceiling, named, or null when the instance
	 * publishes no ceiling at all. It is what the dot at the head of a row is drawn from,
	 * so the dot is always the colour of the fullest bar on that row.
	 */
	function fullest(instance) {
		var found = null;
		[{ name: 'heap', share: heapShare(instance) },
			{ name: 'processor', share: cpuShare(instance) },
			{ name: 'GC overhead', share: gcShare(instance) },
			{ name: 'open files', share: filesShare(instance) },
			{ name: 'pool saturation', share: poolShare(instance) }].forEach(function (entry) {
				if (entry.share !== null && (!found || entry.share > found.share)) {
					found = entry;
				}
			});
		return found;
	}

	/* The pools of one instance ----------------------------------------------------- */

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
			return '—';
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
		return '<div class="gw-pools"><table class="table table-sm align-middle mb-0">'
			+ '<thead><tr class="text-secondary small">'
			+ '<th>Route</th><th>Pool</th><th>Saturation</th><th class="text-end">Active / max</th>'
			+ '<th class="text-end">Idle</th><th class="text-end">Pending</th><th class="text-end">Avg wait</th>'
			+ '</tr></thead><tbody>'
			+ pools.map(poolRow).join('')
			+ '</tbody></table></div>';
	}

	/**
	 * Said under the pools rather than in the loop queue column, which has room for a
	 * figure and not for the property that would produce one.
	 */
	function eventLoopNote(instance) {
		if (instance.instrumentation && instance.instrumentation.httpClient) {
			return '';
		}
		return '<p class="text-secondary small mb-0 mt-3">Event loop counters are off. Set <code>'
			+ CLIENT_PROPERTY + '=true</code> to collect them.</p>';
	}

	/* The fleet table --------------------------------------------------------------- */

	// The columns an unfolded row has to span.
	var COLUMNS = 9;

	/** One figure of a row: the reading, over the bar of its share when there is one. */
	function cell(value, share) {
		return '<td class="gw-col-figure"><div class="fw-semibold text-nowrap">' + value + '</div>'
			+ (share === undefined ? '' : bar(share)) + '</td>';
	}

	function instanceCell(instance, open) {
		var worst = fullest(instance);
		var title = worst ? worst.name + ' at ' + percent(worst.share) + ' of its ceiling'
			: 'no ceiling reported';
		var uri = instance.uri ? '<div class="text-secondary small gw-instance-uri" title="'
			+ escape(instance.uri) + '">' + escape(instance.uri) + '</div>' : '';
		return '<td class="gw-col-instance">'
			+ '<div class="d-flex align-items-center gap-2">'
			+ '<span class="gw-dot ' + barClass(worst ? worst.share : null) + '" title="'
			+ escape(title) + '"></span>'
			+ '<button type="button" class="btn btn-link btn-sm p-0 fw-semibold text-body'
			+ ' text-decoration-none gw-pools-toggle" data-gi-toggle="' + escape(instance.instanceId) + '"'
			+ ' aria-expanded="' + open + '">' + (open ? '&#9662;' : '&#9656;') + ' '
			+ escape(instance.instanceId) + '</button>'
			+ '</div>' + uri + '</td>';
	}

	/**
	 * The pool count of an instance, over the saturation of its fullest pool: the column
	 * says both how many downstream addresses this instance talks to and how close the
	 * busiest of them is to running out of connections.
	 */
	function poolsCell(instance) {
		if (!instance.instrumentation || !instance.instrumentation.connectionPool) {
			return cell('—');
		}
		var pools = instance.pools || [];
		return pools.length ? cell(String(pools.length), poolShare(instance)) : cell('0');
	}

	function queueCell(instance) {
		if (!instance.instrumentation || !instance.instrumentation.httpClient) {
			return cell('—');
		}
		return cell(count(instance.netty.eventLoopPendingTasks)
			+ ' <span class="text-secondary fw-normal small">' + count(instance.netty.eventLoops)
			+ ' loop(s)</span>');
	}

	function row(instance) {
		var open = isExpanded(instance.instanceId);
		var jvm = instance.jvm;
		var system = instance.system;
		var heap = missing(jvm.heapUsedBytes) ? '—'
			: bytes(jvm.heapUsedBytes) + ' / ' + bytes(jvm.heapMaxBytes);
		// The ceiling is seven digits on Linux and read by nobody: the bar carries the
		// share and the cell keeps the count. Both are in the tooltip.
		var files = missing(system.openFiles) ? '—'
			: '<span title="' + count(system.openFiles) + ' of ' + count(system.maxFiles)
				+ ' file descriptor(s)">' + count(system.openFiles) + '</span>';
		return '<tr>'
			+ instanceCell(instance, open)
			+ cell(since(instance.uptimeSeconds))
			+ cell(heap, heapShare(instance))
			+ cell(percent(system.processCpuUsage), cpuShare(instance))
			+ cell(count(jvm.threadsLive) + ' <span class="text-secondary fw-normal small">peak '
				+ count(jvm.threadsPeak) + '</span>')
			+ cell(percent(jvm.gcOverhead), gcShare(instance))
			+ cell(files, filesShare(instance))
			+ poolsCell(instance)
			+ queueCell(instance)
			+ '</tr>'
			+ (open ? detailRow(instance) : '');
	}

	function detailRow(instance) {
		return '<tr><td colspan="' + COLUMNS + '" class="bg-body-tertiary">'
			+ poolSection(instance) + eventLoopNote(instance) + '</td></tr>';
	}

	function table(instances) {
		return '<div class="card border-0 shadow-sm"><div class="card-body">'
			+ '<div class="table-responsive"><table class="table table-sm align-middle mb-0 gw-fleet">'
			// Abbreviated, with the full name in the tooltip: spelled out, four of these
			// headings are wider than the figures under them, and the table stops fitting
			// the page — which is the one thing it is here to do.
			+ '<thead><tr class="text-secondary small">'
			+ '<th>Instance</th><th>Up</th><th>Heap</th>'
			+ '<th title="Processor used by this JVM">CPU</th><th>Threads</th>'
			+ '<th title="Share of uptime spent collecting">GC</th>'
			+ '<th title="Open file descriptors">Files</th>'
			+ '<th title="Connection pools, over the saturation of the busiest">Pools</th>'
			+ '<th title="Tasks queued across the event loops">Queue</th>'
			+ '</tr></thead><tbody>'
			+ instances.map(row).join('')
			+ '</tbody></table></div></div></div>';
	}

	/* Rendering and polling --------------------------------------------------------- */

	function render(payload) {
		snapshot = payload;
		coverageEl.textContent = payload.coverage || '';
		routesByAddress = payload.routesByAddress || {};
		// A stable order, by name. The table is replaced wholesale on every refresh, and a
		// source free to answer in any order would otherwise shuffle the rows under a
		// reader every five seconds.
		var instances = (payload.instances || []).slice().sort(function (left, right) {
			return String(left.instanceId).localeCompare(String(right.instanceId));
		});
		emptyEl.style.display = instances.length ? 'none' : '';
		container.innerHTML = instances.length ? table(instances) : '';
	}

	/*
	 * How old the figures on screen are. It ticks on its own rather than being written on
	 * each refresh: what it is there to reveal is a poll that stopped answering, and a
	 * label only written by a successful load would then stay reassuringly at "just now".
	 */
	function renderAge() {
		if (readAt === null) {
			ageEl.textContent = '';
			return;
		}
		var seconds = Math.round((Date.now() - readAt) / 1000);
		if (seconds < 5) {
			ageEl.textContent = 'just now';
			return;
		}
		if (seconds < 90) {
			ageEl.textContent = seconds + 's ago';
			return;
		}
		ageEl.textContent = Math.round(seconds / 60) + 'm ago';
	}

	/*
	 * Folding is bound to the container rather than to the buttons: the table is replaced
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
			.then(function (payload) {
				render(payload);
				readAt = Date.now();
				renderAge();
			})
			.catch(function () {
				// The age is left where it was: a failed poll makes the figures on screen
				// older, it does not make them younger.
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
	setInterval(renderAge, 1000);
})();
