/*
 * Traffic view. The chart answers a named question rather than exposing raw axes: each
 * preset picks the metrics, splits the plot on the median of both axes and labels the
 * four quadrants, so a bubble's position reads on its own. The table below carries the
 * exact numbers. A "Custom" preset re-opens the raw axis pickers, and a 3D switch adds a
 * third metric via echarts-gl.
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

	// 4xx and 5xx answer different questions, so the view can be read without the client
	// errors: the switch hides their tile, their column and their axes.
	var CLIENT_ERROR_KEYS = ['clientErrorCount', 'clientErrorRate'];

	// Each preset frames a question, then says how to read the resulting picture.
	var PRESETS = {
		optimise: {
			x: 'count', y: 'avgMs', size: 'errorCount', z: 'errorCount',
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
			x: 'count', y: 'errorRate', size: 'errorCount', z: 'errorCount',
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
			x: 'count', y: 'clientErrorRate', size: 'clientErrorCount', z: 'clientErrorCount',
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
			x: 'avgMs', y: 'maxMs', size: 'count', z: 'errorCount',
			help: 'Compares the typical response time (right) with the worst one seen (up). '
				+ 'A bubble far above the others is a route whose worst case is much worse than '
				+ 'its average: look for timeouts, cold starts or a slow dependency.',
			quadrants: null
		},
		custom: { x: 'count', y: 'avgMs', size: 'errorCount', help: '', quadrants: null }
	};

	var dataUrl = chartEl.getAttribute('data-url') || '/ui/metrics/data';
	var chart = echarts.init(chartEl);
	var rows = [];
	var sortKey = 'count';
	var sortDir = -1;
	var pollTimer = null;

	function sel(id) {
		return document.getElementById(id);
	}

	function showClientErrors() {
		var toggle = sel('gm-show-4xx');
		return !toggle || toggle.checked;
	}

	// Hides everything the client errors feed: their tile, their table column, their axis
	// options and the question built on them. A selection left pointing at a hidden metric
	// falls back rather than plotting a column the reader just asked to remove.
	function applyClientErrorFilter() {
		var shown = showClientErrors();
		Array.prototype.forEach.call(document.querySelectorAll('.gm-4xx'), function (element) {
			if (element.tagName === 'OPTION') {
				element.hidden = !shown;
				element.disabled = !shown;
			}
			else {
				element.style.display = shown ? '' : 'none';
			}
		});
		if (!shown) {
			if (sel('gm-preset').value === 'rejected') {
				sel('gm-preset').value = 'optimise';
			}
			['gm-x', 'gm-y', 'gm-size', 'gm-z'].forEach(function (id) {
				if (CLIENT_ERROR_KEYS.indexOf(sel(id).value) >= 0) {
					sel(id).value = (id === 'gm-x') ? 'count' : 'errorCount';
				}
			});
		}
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

	// Padded axis bounds; keeps the quadrant areas aligned with the visible range.
	function bounds(values) {
		var min = Math.min.apply(null, values);
		var max = Math.max.apply(null, values);
		if (max === min) {
			return { min: min - 1, max: max + 1 };
		}
		var pad = (max - min) * 0.1;
		return { min: min - pad, max: max + pad };
	}

	function sizer(key) {
		var values = rows.map(function (row) {
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
			+ (showClientErrors()
				? '<br>4xx: ' + row.clientErrorCount + ' (' + (row.clientErrorRate * 100).toFixed(1) + '%)' : '')
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
		if (!quadrants || rows.length < 3) {
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
		var is3d = sel('gm-3d').checked;
		var xk = sel('gm-x').value;
		var yk = sel('gm-y').value;
		var sk = sel('gm-size').value;
		var zk = sel('gm-z').value;
		var sizeFn = sizer(sk);

		sel('gm-legend').textContent = 'Bubble size = ' + LABELS[sk]
			+ ' · colour = error rate (green none, amber some, red heavy) · hover a bubble for details.';

		if (is3d) {
			chart.setOption({
				tooltip: { formatter: tooltip },
				xAxis3D: { name: LABELS[xk], type: 'value' },
				yAxis3D: { name: LABELS[yk], type: 'value' },
				zAxis3D: { name: LABELS[zk], type: 'value' },
				grid3D: { viewControl: { autoRotate: false, rotateSensitivity: 2 } },
				series: [{
					type: 'scatter3D',
					data: rows.map(function (row) {
						return point(row, [xk, yk, zk], sizeFn, sk);
					})
				}]
			}, true);
			return;
		}

		var xs = rows.map(function (row) {
			return value(row, xk);
		});
		var ys = rows.map(function (row) {
			return value(row, yk);
		});
		var xb = bounds(xs);
		var yb = bounds(ys);
		var guide = guides(xs, ys, xb, yb, config.quadrants);

		chart.setOption({
			tooltip: { formatter: tooltip },
			grid: { left: 70, right: 30, top: 24, bottom: 56 },
			xAxis: {
				name: LABELS[xk], type: 'value', nameLocation: 'middle', nameGap: 32,
				min: xb.min, max: xb.max
			},
			yAxis: { name: LABELS[yk], type: 'value', min: yb.min, max: yb.max },
			series: [{
				type: 'scatter',
				data: rows.map(function (row) {
					return point(row, [xk, yk], sizeFn, sk);
				}),
				label: {
					show: true, formatter: '{b}', position: 'top', fontSize: 10, color: '#64748b'
				},
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
		sel('gm-table-empty').style.display = rows.length ? 'none' : '';
		rows.slice().sort(function (a, b) {
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
			if (showClientErrors()) {
				tr.appendChild(cell(row.clientErrorCount, true));
			}
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

	function render() {
		applyClientErrorFilter();
		var config = preset();
		var isCustom = sel('gm-preset').value === 'custom';
		var is3d = sel('gm-3d').checked;

		// A preset drives the axes; Custom hands them back to the user.
		if (!isCustom) {
			sel('gm-x').value = config.x;
			sel('gm-y').value = config.y;
			sel('gm-size').value = config.size;
		}
		sel('gm-custom').style.display = isCustom ? 'flex' : 'none';
		sel('gm-z-wrap').style.display = is3d ? '' : 'none';
		sel('gm-help').style.display = config.help ? '' : 'none';
		sel('gm-help').textContent = config.help;

		renderKpis();
		renderTable();

		if (!rows.length) {
			chart.clear();
			sel('gm-empty').style.display = '';
			sel('gm-legend').textContent = '';
			return;
		}
		sel('gm-empty').style.display = 'none';
		renderChart();
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

	sel('gm-preset').addEventListener('change', function () {
		var config = preset();
		if (config.z) {
			sel('gm-z').value = config.z;
		}
		render();
	});
	['gm-x', 'gm-y', 'gm-size', 'gm-z', 'gm-3d'].forEach(function (id) {
		sel(id).addEventListener('change', render);
	});
	sel('gm-refresh').addEventListener('click', load);
	sel('gm-show-4xx').addEventListener('change', render);
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
	});

	// Restored before the first render, so the view draws the chart it was left on rather
	// than the default one.
	['gm-preset', 'gm-x', 'gm-y', 'gm-size', 'gm-z', 'gm-show-4xx', 'gm-3d', 'gm-auto']
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
