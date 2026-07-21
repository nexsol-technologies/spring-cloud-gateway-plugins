/*
 * Traffic view: renders the per-route gateway metrics as an ECharts bubble chart,
 * switchable between 2D (scatter) and 3D (scatter3D via echarts-gl). Axis and bubble-size
 * metrics are picked from the toolbar selects; data is polled from the /data endpoint.
 */
(function () {
	var el = document.getElementById('gm-chart');
	if (!el || typeof echarts === 'undefined') {
		return;
	}
	var dataUrl = el.getAttribute('data-url') || '/ui/metrics/data';
	var chart = echarts.init(el);
	var rows = [];
	var pollTimer = null;

	var LABELS = {
		count: 'Calls',
		avgMs: 'Avg latency (ms)',
		maxMs: 'Max latency (ms)',
		errorCount: 'Errors',
		errorRate: 'Error rate (%)'
	};

	function sel(id) {
		return document.getElementById(id);
	}

	// Normalises a route metric to the plotted value for the given axis key.
	function value(row, key) {
		if (key === 'errorRate') {
			return Math.round(row.errorRate * 1000) / 10;
		}
		if (key === 'avgMs' || key === 'maxMs') {
			return Math.round(row[key] * 10) / 10;
		}
		return row[key];
	}

	// Maps the size metric onto a pixel radius range.
	function sizer(key) {
		var values = rows.map(function (row) {
			return value(row, key);
		});
		var min = Math.min.apply(null, values);
		var max = Math.max.apply(null, values);
		return function (v) {
			if (max <= min) {
				return 22;
			}
			return 10 + 34 * ((v - min) / (max - min));
		};
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
			+ '<br>Errors: ' + row.errorCount + ' (' + (row.errorRate * 100).toFixed(1) + '%)';
	}

	function point(row, keys, sizeFn, sizeKey) {
		var coords = keys.map(function (key) {
			return value(row, key);
		});
		return {
			name: row.routeId,
			value: coords,
			symbolSize: sizeFn(value(row, sizeKey)),
			itemStyle: { color: colourFor(row.errorRate) }
		};
	}

	function render() {
		var is3d = sel('gm-3d').checked;
		sel('gm-z-wrap').style.display = is3d ? '' : 'none';

		if (!rows.length) {
			chart.clear();
			sel('gm-empty').style.display = '';
			return;
		}
		sel('gm-empty').style.display = 'none';

		var xk = sel('gm-x').value;
		var yk = sel('gm-y').value;
		var zk = sel('gm-z').value;
		var sk = sel('gm-size').value;
		var sizeFn = sizer(sk);

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
		}
		else {
			chart.setOption({
				tooltip: { formatter: tooltip },
				grid: { left: 64, right: 28, top: 24, bottom: 52 },
				xAxis: { name: LABELS[xk], type: 'value', nameLocation: 'middle', nameGap: 30 },
				yAxis: { name: LABELS[yk], type: 'value' },
				series: [{
					type: 'scatter',
					data: rows.map(function (row) {
						return point(row, [xk, yk], sizeFn, sk);
					})
				}]
			}, true);
		}
	}

	function load() {
		fetch(dataUrl, { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(function (json) {
				rows = json || [];
				render();
			})
			.catch(function () {
				rows = [];
				render();
			});
	}

	function toggleAuto() {
		if (sel('gm-auto').checked) {
			pollTimer = setInterval(load, 5000);
		}
		else if (pollTimer) {
			clearInterval(pollTimer);
			pollTimer = null;
		}
	}

	['gm-x', 'gm-y', 'gm-z', 'gm-size', 'gm-3d'].forEach(function (id) {
		sel(id).addEventListener('change', render);
	});
	sel('gm-refresh').addEventListener('click', load);
	sel('gm-auto').addEventListener('change', toggleAuto);
	window.addEventListener('resize', function () {
		chart.resize();
	});

	load();
})();
