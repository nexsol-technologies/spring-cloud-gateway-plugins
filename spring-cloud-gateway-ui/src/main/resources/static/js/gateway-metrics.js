/*
 * Traffic view, read top to bottom: the map, then the ranking, then the numbers.
 *
 * The map answers a named question rather than exposing raw axes: each preset picks the
 * metrics, splits the plot on the median of both axes and labels the four quadrants, so a
 * bubble's position reads on its own. A "Custom" preset re-opens the raw axis pickers.
 *
 * The ranking below it carries what a position cannot: the product of both axes, which is
 * the grandeur the question is really about — the time a route costs, the errors it
 * accounts for. The table at the bottom carries the exact numbers.
 *
 * A gateway with hundreds of routes is what the rest of the controls answer: the plot is
 * restricted to the routes a filter keeps, capped to the busiest N, drawn on axes that
 * turn logarithmic when the spread calls for it, and only the routes the question is about
 * are named. Any of them can be zoomed into.
 */
(function () {
	var chartEl = document.getElementById('gm-chart');
	if (!chartEl || typeof echarts === 'undefined') {
		return;
	}

	var LABELS = {
		count: 'Calls',
		avgMs: 'Avg latency (ms)',
		maxMs: 'Max latency (ms)',
		clientErrorCount: 'Client errors (4xx)',
		clientErrorRate: 'Client error rate (%)',
		errorCount: 'Server errors (5xx)',
		errorRate: 'Server error rate (%)'
	};

	// Bubbles carrying their route id. Past a dozen the names cover the plot they describe.
	var LABELLED = 12;

	// Bars in the ranking. Twenty routes is a page that can be acted on.
	var RANKED = 20;

	// Each preset frames a question, then says how to read the resulting picture.
	var PRESETS = {
		optimise: {
			x: 'count', y: 'avgMs', size: 'errorCount',
			rank: {
				label: 'time spent',
				of: function (row) {
					return row.count * row.avgMs;
				},
				format: duration,
				help: 'Calls × average latency: the time the gateway actually spends in a route. '
					+ 'One called 100 000 times at 10 ms costs more than one called twice at 10 s, '
					+ 'which is what the map cannot show — it is a product of both of its axes.'
			},
			help: 'Right = called often, up = slow. The dashed lines are the median route, '
				+ 'so anything in the top-right is both busier and slower than half your routes: '
				+ 'that is where an optimisation pays off the most.',
			quadrants: {
				tr: 'Busy & slow — optimise here',
				tl: 'Slow but rarely called',
				br: 'Busy & fast — healthy',
				bl: 'Quiet & fast'
			}
		},
		failing: {
			x: 'count', y: 'errorRate', size: 'errorCount',
			rank: {
				label: 'server errors',
				of: function (row) {
					return row.errorCount;
				},
				format: suffixed(' errors'),
				help: 'The absolute number of 5xx rather than the rate: half of four calls failing '
					+ 'is not an outage.'
			},
			help: 'Right = called often, up = fails often. Top-right routes fail on traffic that '
				+ 'actually matters — fix those first. Bubble size is the absolute number of 5xx.',
			quadrants: {
				tr: 'Busy & failing — fix first',
				tl: 'Failing but rarely called',
				br: 'Busy & healthy',
				bl: 'Quiet & healthy'
			}
		},
		rejected: {
			x: 'count', y: 'clientErrorRate', size: 'clientErrorCount',
			rank: {
				label: 'rejected calls',
				of: function (row) {
					return row.clientErrorCount;
				},
				format: suffixed(' rejected'),
				help: 'The absolute number of 4xx: how many callers were actually turned away.'
			},
			help: 'Right = called often, up = rejected often. These are 4xx: the caller was turned '
				+ 'away, not the backend failing. A route high on this chart usually means a wrong '
				+ 'path, a missing permission or a client calling it wrong — bubble size is the '
				+ 'absolute number of 4xx.',
			quadrants: {
				tr: 'Busy & rejected — check auth or paths',
				tl: 'Rejected but rarely called',
				br: 'Busy & accepted',
				bl: 'Quiet & accepted'
			}
		},
		spikes: {
			x: 'avgMs', y: 'maxMs', size: 'count',
			rank: {
				label: 'worst-case overshoot',
				of: function (row) {
					return row.maxMs - row.avgMs;
				},
				format: suffixed(' ms'),
				help: 'How far the worst response sits above the typical one.'
			},
			help: 'Compares the typical response time (right) with the worst one seen (up). '
				+ 'A bubble far above the others is a route whose worst case is much worse than '
				+ 'its average: look for timeouts, cold starts or a slow dependency.',
			quadrants: null
		},
		custom: { x: 'count', y: 'avgMs', size: 'errorCount', help: '', quadrants: null }
	};

	var dataUrl = chartEl.getAttribute('data-url') || '/ui/metrics/data';
	var theme = window.gatewayUi.theme() === 'dark' ? 'dark' : null;
	var chart = echarts.init(chartEl, theme);
	var barsEl = document.getElementById('gm-bars');
	var bars = echarts.init(barsEl, theme);
	var rows = [];
	var drawn = [];
	var extents = null;
	var sortKey = 'count';
	var sortDir = -1;
	var pollTimer = null;

	function sel(id) {
		return document.getElementById(id);
	}

	function preset() {
		return PRESETS[sel('gm-preset').value] || PRESETS.optimise;
	}

	// Value actually plotted / displayed for a metric key.
	function value(row, key) {
		if (key === 'errorRate' || key === 'clientErrorRate') {
			return Math.round(row[key] * 1000) / 10;
		}
		if (key === 'avgMs' || key === 'maxMs') {
			return Math.round(row[key] * 10) / 10;
		}
		return row[key];
	}

	function colourFor(errorRate) {
		if (errorRate <= 0) {
			return '#22c55e';
		}
		if (errorRate < 0.05) {
			return '#84cc16';
		}
		if (errorRate < 0.2) {
			return '#f59e0b';
		}
		return '#ef4444';
	}

	function median(values) {
		var sorted = values.slice().sort(function (a, b) {
			return a - b;
		});
		var mid = Math.floor(sorted.length / 2);
		return (sorted.length % 2) ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
	}

	// Padded axis bounds; keeps the quadrant areas aligned with the visible range. A zoom
	// picks its window inside them rather than replacing them, so they hold at every zoom
	// level.
	function bounds(values, log) {
		var min = Math.min.apply(null, values);
		var max = Math.max.apply(null, values);
		if (log) {
			// Already logged: a whole number is a decade, which is where the axis is cut.
			return { min: Math.floor(min), max: Math.max(Math.ceil(max), Math.floor(min) + 1) };
		}
		if (max === min) {
			return { min: min - 1, max: max + 1 };
		}
		var pad = (max - min) * 0.1;
		// Rounded: an explicit bound is printed as the axis label as given, and the float
		// noise of the padding would render it as 3.0000000004 rather than 3.4.
		return { min: round(min - pad), max: round(max + pad) };
	}

	function round(value) {
		return Math.round(value * 1000) / 1000;
	}

	function sizer(key) {
		var values = drawn.map(function (row) {
			return value(row, key);
		});
		var min = Math.min.apply(null, values);
		var max = Math.max.apply(null, values);
		return function (v) {
			if (max <= min) {
				return 20;
			}
			return 10 + 32 * ((v - min) / (max - min));
		};
	}

	// Free-text filter over route ids: space-separated terms, all of which must match, a
	// term prefixed with '-' excluding what it matches. A gateway that discovers its routes
	// carries hundreds of them named after the client that found them, and typing
	// '-discoveryclient' is what takes those out of the picture.
	function accepts(routeId) {
		var text = (sel('gm-filter').value || '').trim().toLowerCase();
		if (!text) {
			return true;
		}
		var id = routeId.toLowerCase();
		return text.split(/\s+/).every(function (term) {
			if (term.charAt(0) === '-') {
				return term.length === 1 || id.indexOf(term.substring(1)) < 0;
			}
			return id.indexOf(term) >= 0;
		});
	}

	// The routes actually drawn: what the filter keeps, capped to the busiest N. The chart
	// and the table below both read from this; the tiles above stay on the whole payload.
	function visible() {
		var kept = rows.filter(function (row) {
			return accepts(row.routeId);
		});
		var top = parseInt(sel('gm-top').value, 10);
		if (!top || kept.length <= top) {
			return kept;
		}
		return kept.slice().sort(function (a, b) {
			return b.count - a.count;
		}).slice(0, top);
	}

	function positives(values) {
		return values.filter(function (v) {
			return v > 0;
		});
	}

	// Whether an axis is drawn logarithmic. On 'auto' it is as soon as the largest value
	// dwarfs the median one: a linear axis then stacks most of the routes on one pixel.
	function logarithmic(values) {
		var mode = sel('gm-scale').value;
		if (mode !== 'auto') {
			return mode === 'log';
		}
		var positive = positives(values);
		return positive.length >= 3 && Math.max.apply(null, positive) >= 50 * median(positive);
	}

	// The values are logged here and drawn on a plain axis rather than handed to a log
	// axis, which ECharts zooms linearly in value space: nine tenths of the slider would
	// then cover the top decade. Logged, a zoom covers the same span of decades wherever
	// it lands, and the axis labels are turned back into the real numbers.
	//
	// Nothing can be logged at zero, and most routes have none of whatever the axis
	// carries. Those are drawn on a floor just under the smallest real value, where they
	// read as "none" without breaking the scale.
	function plot(values, log) {
		if (!log) {
			return values;
		}
		var positive = positives(values);
		var floor = positive.length ? Math.min.apply(null, positive) / 2 : 0.1;
		return values.map(function (v) {
			return Math.log(Math.max(v, floor)) / Math.LN10;
		});
	}

	// A time budget reads in the unit it is worth: a route can cost milliseconds or hours.
	function duration(ms) {
		if (ms >= 3600000) {
			return (ms / 3600000).toFixed(1) + ' h';
		}
		if (ms >= 60000) {
			return (ms / 60000).toFixed(1) + ' min';
		}
		if (ms >= 1000) {
			return (ms / 1000).toFixed(1) + ' s';
		}
		return Math.round(ms) + ' ms';
	}

	function suffixed(suffix) {
		return function (v) {
			return Math.round(v).toLocaleString('en-US') + suffix;
		};
	}

	// What "top" means for the question being asked. A preset carries its own; Custom
	// multiplies the two axes it was given, which is the distance from the origin the map
	// is read on.
	function ranking() {
		var config = preset();
		if (config.rank) {
			return config.rank;
		}
		var xk = sel('gm-x').value;
		var yk = sel('gm-y').value;
		return {
			label: LABELS[xk] + ' × ' + LABELS[yk],
			of: function (row) {
				return value(row, xk) * value(row, yk);
			},
			format: suffixed(''),
			help: 'Both axes multiplied: the routes furthest from the origin of the map.'
		};
	}

	// Axis label of a logged value: the number the reader knows, not its exponent. Grouped
	// the way echarts groups the labels of a plain axis, rather than the way the browser of
	// whoever is reading happens to.
	function unlog(logged) {
		var raw = Math.pow(10, logged);
		if (raw >= 1000) {
			return Math.round(raw).toLocaleString('en-US');
		}
		return String(raw >= 10 ? Math.round(raw) : Math.round(raw * 10) / 10);
	}

	// The routes the question is actually about: the head of the ranking the bars below
	// draw. Naming every bubble is what makes the plot unreadable, so only these carry a
	// label -- the tooltip names any other one on hover.
	function named(rank) {
		var top = {};
		drawn.map(function (row, index) {
			return { index: index, score: rank.of(row) };
		}).sort(function (a, b) {
			return b.score - a.score;
		}).slice(0, LABELLED).forEach(function (entry) {
			top[entry.index] = true;
		});
		return top;
	}

	// Said out loud whenever the plot and the table are not showing every route, since the
	// tiles above are left on the whole gateway and their totals would otherwise read as
	// the totals of what is drawn.
	function scope() {
		if (drawn.length === rows.length) {
			return '';
		}
		return ' · ' + drawn.length + ' of ' + rows.length
			+ ' routes shown — the tiles above cover the whole gateway.';
	}

	function axis(key, log, range) {
		var spec = {
			name: LABELS[key] + (log ? ' — log scale' : ''), type: 'value',
			min: range.min, max: range.max
		};
		if (log) {
			spec.axisLabel = { formatter: unlog };
		}
		return spec;
	}

	function findRoute(routeId) {
		for (var i = 0; i < rows.length; i++) {
			if (rows[i].routeId === routeId) {
				return rows[i];
			}
		}
		return null;
	}

	function tooltip(params) {
		var row = findRoute(params.name);
		if (!row) {
			return params.name;
		}
		return '<strong>' + row.routeId + '</strong>' + (row.uri ? '<br>' + row.uri : '')
			+ '<br>Calls: ' + row.count
			+ '<br>Avg: ' + row.avgMs.toFixed(1) + ' ms'
			+ '<br>Max: ' + row.maxMs.toFixed(1) + ' ms'
			+ '<br>4xx: ' + row.clientErrorCount + ' (' + (row.clientErrorRate * 100).toFixed(1) + '%)'
			+ '<br>5xx: ' + row.errorCount + ' (' + (row.errorRate * 100).toFixed(1) + '%)';
	}

	function point(row, keys, sizeFn, sizeKey) {
		return {
			name: row.routeId,
			value: keys.map(function (key) {
				return value(row, key);
			}),
			symbolSize: sizeFn(value(row, sizeKey)),
			itemStyle: { color: colourFor(row.errorRate) }
		};
	}

	// Median cross plus the four labelled quadrants that make a position readable.
	function guides(xs, ys, xb, yb, quadrants) {
		var mx = median(xs);
		var my = median(ys);
		var markLine = {
			silent: true,
			symbol: 'none',
			lineStyle: { type: 'dashed', color: '#cbd5e1' },
			label: { show: false },
			data: [{ xAxis: mx }, { yAxis: my }]
		};
		if (!quadrants || drawn.length < 3) {
			return { markLine: markLine };
		}
		function area(x0, y0, x1, y1, text, tint) {
			return [
				{
					xAxis: x0, yAxis: y0,
					itemStyle: { color: tint || 'transparent' },
					label: { formatter: text }
				},
				{ xAxis: x1, yAxis: y1 }
			];
		}
		return {
			markLine: markLine,
			markArea: {
				silent: true,
				label: { show: true, position: 'inside', color: '#94a3b8', fontSize: 11 },
				data: [
					area(mx, my, xb.max, yb.max, quadrants.tr, 'rgba(239,68,68,0.05)'),
					area(xb.min, my, mx, yb.max, quadrants.tl),
					area(mx, yb.min, xb.max, my, quadrants.br),
					area(xb.min, yb.min, mx, my, quadrants.bl)
				]
			}
		};
	}

	function renderChart() {
		var config = preset();
		var xk = sel('gm-x').value;
		var yk = sel('gm-y').value;
		var sk = sel('gm-size').value;
		var sizeFn = sizer(sk);

		sel('gm-legend').textContent = 'Bubble size = ' + LABELS[sk]
			+ ' · colour = error rate (green none, amber some, red heavy) · hover a bubble for '
			+ 'details · ctrl + scroll to zoom, drag to pan.' + scope();

		var xs = drawn.map(function (row) {
			return value(row, xk);
		});
		var ys = drawn.map(function (row) {
			return value(row, yk);
		});
		var xLog = logarithmic(xs);
		var yLog = logarithmic(ys);
		var px = plot(xs, xLog);
		var py = plot(ys, yLog);
		var xb = bounds(px, xLog);
		var yb = bounds(py, yLog);
		// The wheel zooms around the cursor, which it can only place in a window once it
		// knows what the axis spans.
		extents = { x: xb, y: yb };
		var guide = guides(px, py, xb, yb, config.quadrants);
		var labelled = named(ranking());
		var xAxis = axis(xk, xLog, xb);
		xAxis.nameLocation = 'middle';
		xAxis.nameGap = 32;

		chart.setOption({
			// The bundled dark theme paints a canvas of its own, dropped so the chart keeps
			// sitting on the card that hosts it.
			backgroundColor: 'transparent',
			tooltip: { formatter: tooltip },
			grid: { left: 70, right: 58, top: 24, bottom: 78 },
			xAxis: xAxis,
			yAxis: axis(yk, yLog, yb),
			// Drag on the plot and a slider per axis: a corner of the cloud is read by
			// zooming into it. 'none' keeps the points outside the window in the series,
			// so the median cross and the quadrants stay where they were computed.
			//
			// 'zoomLock' is what takes the wheel out of the hands of echarts, and it is
			// the only thing that does: bound to a modifier or not, its inside zoom
			// consumes every wheel it is registered for before looking at the modifier,
			// and a plot this tall would then trap the scroll of the page it sits in.
			// Locked, the controller only registers the drag that pans; the wheel is read
			// below instead.
			dataZoom: [
				{ type: 'inside', xAxisIndex: 0, filterMode: 'none', zoomLock: true },
				{ type: 'inside', yAxisIndex: 0, filterMode: 'none', zoomLock: true },
				{ type: 'slider', xAxisIndex: 0, filterMode: 'none', bottom: 10, height: 18 },
				{
					type: 'slider', yAxisIndex: 0, filterMode: 'none', right: 10, width: 18,
					top: 24, bottom: 78
				}
			],
			series: [{
				type: 'scatter',
				data: drawn.map(function (row, index) {
					var item = point(row, [xk, yk], sizeFn, sk);
					item.value = [px[index], py[index]];
					item.label = { show: labelled[index] === true };
					return item;
				}),
				label: {
					show: true, formatter: '{b}', position: 'top', fontSize: 10, color: '#64748b'
				},
				// Even a dozen labels collide once the bubbles pile up: the ones that
				// cannot be placed are dropped rather than drawn over their neighbour.
				labelLayout: { hideOverlap: true },
				markLine: guide.markLine,
				markArea: guide.markArea
			}]
		}, true);
	}

	function renderKpis() {
		var calls = 0;
		var clientErrors = 0;
		var errors = 0;
		var weighted = 0;
		rows.forEach(function (row) {
			calls += row.count;
			clientErrors += row.clientErrorCount;
			errors += row.errorCount;
			weighted += row.avgMs * row.count;
		});
		sel('gm-kpi-routes').textContent = rows.length;
		sel('gm-kpi-calls').textContent = calls;
		sel('gm-kpi-avg').textContent = calls ? (weighted / calls).toFixed(1) + ' ms' : '—';
		sel('gm-kpi-client-errors').textContent = clientErrors;
		sel('gm-kpi-errors').textContent = errors;
	}

	function cell(text, alignEnd) {
		var td = document.createElement('td');
		td.textContent = text;
		if (alignEnd) {
			td.className = 'text-end';
		}
		return td;
	}

	function renderTable() {
		var body = sel('gm-tbody');
		body.innerHTML = '';
		sel('gm-table-empty').style.display = drawn.length ? 'none' : '';
		sel('gm-table-note').textContent = scope();
		drawn.slice().sort(function (a, b) {
			var va = a[sortKey];
			var vb = b[sortKey];
			if (typeof va === 'string' || typeof vb === 'string') {
				return sortDir * String(va || '').localeCompare(String(vb || ''));
			}
			return sortDir * (va - vb);
		}).forEach(function (row) {
			var tr = document.createElement('tr');
			tr.appendChild(cell(row.routeId));
			tr.appendChild(cell(row.uri || '—'));
			tr.appendChild(cell(row.count, true));
			tr.appendChild(cell(row.avgMs.toFixed(1), true));
			tr.appendChild(cell(row.maxMs.toFixed(1), true));
			tr.appendChild(cell(row.clientErrorCount, true));
			tr.appendChild(cell(row.errorCount, true));
			var rate = document.createElement('td');
			rate.className = 'text-end';
			var badge = document.createElement('span');
			badge.className = 'badge ' + (row.errorRate <= 0 ? 'text-bg-success'
				: (row.errorRate < 0.05 ? 'text-bg-light text-dark'
					: (row.errorRate < 0.2 ? 'text-bg-warning' : 'text-bg-danger')));
			badge.textContent = (row.errorRate * 100).toFixed(1) + '%';
			rate.appendChild(badge);
			tr.appendChild(rate);
			body.appendChild(tr);
		});
	}

	// A rounded 100% would claim the bars carry everything while routes are left out of
	// them, so a remainder that rounds away is spelled out as one.
	function share(carried, total, truncated) {
		var percent = 100 * carried / total;
		if (truncated && percent > 99.5) {
			return '>99%';
		}
		return Math.round(percent) + '%';
	}

	// The ranking the map cannot draw: the routes that carry the most of whatever the
	// question is about, biggest first, with what the twenty of them add up to.
	// Where the cursor sits on an axis, as the percentage a zoom window is expressed in.
	function percent(value, extent) {
		return 100 * (value - extent.min) / (extent.max - extent.min);
	}

	// The window a wheel notch leaves behind: the span scaled, anchored on the cursor so
	// the bubble under it stays under it, and kept inside the axis.
	function windowed(zoom, anchor, ratio, index) {
		var span = Math.min(100, (zoom.end - zoom.start) * ratio);
		var start = Math.max(0, Math.min(100 - span, anchor - (anchor - zoom.start) * ratio));
		return { type: 'dataZoom', dataZoomIndex: index, start: start, end: start + span };
	}

	// Ctrl + wheel zooms the map; a wheel alone is left to the page it sits in. Ctrl is
	// also what a browser zooms its own page with, hence the preventDefault.
	function zoomOnCtrlWheel(event) {
		if (!event.ctrlKey || !extents || !drawn.length) {
			return;
		}
		event.preventDefault();
		var box = chartEl.getBoundingClientRect();
		var at = chart.convertFromPixel({ gridIndex: 0 }, [event.clientX - box.left, event.clientY - box.top]);
		if (!at) {
			return;
		}
		var ratio = event.deltaY < 0 ? 1 / 1.25 : 1.25;
		var zooms = chart.getOption().dataZoom;
		chart.dispatchAction(windowed(zooms[0], percent(at[0], extents.x), ratio, 0));
		chart.dispatchAction(windowed(zooms[1], percent(at[1], extents.y), ratio, 1));
	}

	function renderBars() {
		var rank = ranking();
		var scored = drawn.map(function (row) {
			return { row: row, score: rank.of(row) };
		}).filter(function (entry) {
			return entry.score > 0;
		}).sort(function (a, b) {
			return b.score - a.score;
		});
		if (!scored.length) {
			sel('gm-bars-card').style.display = 'none';
			return;
		}
		sel('gm-bars-card').style.display = '';

		var top = scored.slice(0, RANKED);
		var total = 0;
		var carried = 0;
		scored.forEach(function (entry, index) {
			total += entry.score;
			if (index < RANKED) {
				carried += entry.score;
			}
		});
		sel('gm-bars-title').textContent = 'Top ' + top.length + ' by ' + rank.label;
		sel('gm-bars-help').textContent = rank.help + ' These ' + top.length + ' carry '
			+ share(carried, total, top.length < scored.length) + ' of the ' + rank.label
			+ ' of the ' + drawn.length + (drawn.length > 1 ? ' routes' : ' route') + ' above.';

		// One row per bar: the chart is exactly as tall as it has routes to name, rather
		// than squeezing twenty of them into a fixed height.
		barsEl.style.height = (top.length * 26 + 40) + 'px';
		bars.setOption({
			backgroundColor: 'transparent',
			tooltip: { formatter: tooltip },
			grid: { left: 8, right: 110, top: 8, bottom: 8, containLabel: true },
			xAxis: { type: 'value', show: false },
			yAxis: {
				type: 'category', inverse: true,
				data: top.map(function (entry) {
					return entry.row.routeId;
				}),
				axisTick: { show: false },
				axisLine: { show: false },
				axisLabel: { fontSize: 11, width: 280, overflow: 'truncate' }
			},
			series: [{
				type: 'bar',
				barMaxWidth: 16,
				data: top.map(function (entry) {
					return {
						name: entry.row.routeId, value: entry.score,
						itemStyle: { color: colourFor(entry.row.errorRate) }
					};
				}),
				label: {
					show: true, position: 'right', fontSize: 11, color: '#64748b',
					formatter: function (params) {
						return rank.format(params.value);
					}
				}
			}]
		}, true);
		bars.resize();
	}

	function render() {
		var config = preset();
		var isCustom = sel('gm-preset').value === 'custom';

		// A preset drives the axes; Custom hands them back to the user.
		if (!isCustom) {
			sel('gm-x').value = config.x;
			sel('gm-y').value = config.y;
			sel('gm-size').value = config.size;
		}
		sel('gm-custom').style.display = isCustom ? 'flex' : 'none';
		sel('gm-help').style.display = config.help ? '' : 'none';
		sel('gm-help').textContent = config.help;

		drawn = visible();
		renderKpis();
		renderTable();

		if (!drawn.length) {
			// Hidden rather than cleared: an empty plot of the height of a full one would
			// push the message that explains it off the screen.
			chart.clear();
			chartEl.style.display = 'none';
			bars.clear();
			sel('gm-bars-card').style.display = 'none';
			sel('gm-empty').textContent = rows.length
				? 'No route matches this filter.'
				: 'No route metrics yet — send some traffic through the gateway, then refresh.';
			sel('gm-empty').style.display = '';
			sel('gm-legend').textContent = '';
			return;
		}
		sel('gm-empty').style.display = 'none';
		chartEl.style.display = '';
		renderChart();
		renderBars();
	}

	function load() {
		fetch(dataUrl, { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(function (json) {
				rows = (json && json.metrics) || [];
				// What these figures cover: one instance, or every instance consolidated
				// by a metrics provider. Shown next to the chart so a count is never read
				// as more than it is.
				var coverage = sel('gm-coverage');
				if (coverage) {
					coverage.textContent = (json && json.coverage) ? json.coverage : '';
				}
				render();
			})
			.catch(function () {
				rows = [];
				render();
			});
	}

	['gm-preset', 'gm-x', 'gm-y', 'gm-size', 'gm-top', 'gm-scale'].forEach(function (id) {
		sel(id).addEventListener('change', render);
	});
	// Not passive: a ctrl + wheel that zooms the map must not also zoom the browser.
	chartEl.addEventListener('wheel', zoomOnCtrlWheel, { passive: false });
	// On input rather than on change: the plot follows what is being typed.
	sel('gm-filter').addEventListener('input', render);
	// A render replaces the option rather than merging into it, which is what puts the axes
	// back to the range they were computed with.
	sel('gm-reset-zoom').addEventListener('click', render);
	sel('gm-refresh').addEventListener('click', load);
	sel('gm-auto').addEventListener('change', function () {
		if (sel('gm-auto').checked) {
			pollTimer = setInterval(load, 5000);
		}
		else if (pollTimer) {
			clearInterval(pollTimer);
			pollTimer = null;
		}
	});
	Array.prototype.forEach.call(document.querySelectorAll('.gw-sortable'), function (th) {
		th.addEventListener('click', function () {
			var key = th.getAttribute('data-sort');
			sortDir = (key === sortKey) ? -sortDir : -1;
			sortKey = key;
			renderTable();
		});
	});
	window.addEventListener('resize', function () {
		chart.resize();
		bars.resize();
	});

	// Restored before the first render, so the view draws the chart it was left on rather
	// than the default one.
	['gm-preset', 'gm-x', 'gm-y', 'gm-size', 'gm-auto', 'gm-filter', 'gm-top', 'gm-scale']
		.forEach(function (id) {
			window.gatewayUi.remember(sel(id));
		});
	// The auto switch drives a timer started by its change handler, which restoring the
	// switch does not fire.
	if (sel('gm-auto').checked) {
		pollTimer = setInterval(load, 5000);
	}

	load();
})();
