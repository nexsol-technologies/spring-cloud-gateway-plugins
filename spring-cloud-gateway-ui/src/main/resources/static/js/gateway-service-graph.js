/*
 * Service graph view. The picture is the graph the active source reported: nodes are the
 * endpoints, edges the calls between them. Filtering narrows what is drawn without asking
 * the gateway again — the payload is small, and a graph that redraws while it is being
 * read is unreadable, which is also why nothing here polls.
 */
(function () {
	var chartEl = document.getElementById('gg-chart');
	if (!chartEl || typeof echarts === 'undefined') {
		return;
	}

	// Node colours. A caller only ever called; a service was reached by the gateway at
	// least once, whichever side of an edge it also appears on.
	var SERVICE_COLOR = '#4f83cc';

	var CALLER_COLOR = '#8e8e93';

	var dataUrl = chartEl.getAttribute('data-url') || '/ui/service-graph/data';
	var chart = echarts.init(chartEl, window.gatewayUi.theme() === 'dark' ? 'dark' : null);
	var nodes = [];
	var edges = [];
	// Positions the force layout settled on, kept so a refresh redraws the same picture
	// instead of shuffling it. Cleared when the layout is unfrozen.
	var positions = {};
	var sortKey = 'calls';
	var sortDir = -1;

	function sel(id) {
		return document.getElementById(id);
	}

	function frozen() {
		var toggle = sel('gg-freeze');
		return !toggle || toggle.checked;
	}

	function errorRate(edge) {
		return (edge.calls > 0) ? edge.errors / edge.calls : 0;
	}

	/**
	 * The edges left after the filters: a name fragment matched on either endpoint, a
	 * minimum number of calls, and failures only. The focused node keeps the edges it
	 * takes part in, whichever side it is on, so focusing shows a node with its immediate
	 * neighbourhood rather than a node alone.
	 */
	function visibleEdges() {
		var focus = sel('gg-focus').value;
		var needle = (sel('gg-search').value || '').trim().toLowerCase();
		var minCalls = parseInt(sel('gg-min-calls').value, 10) || 0;
		var failingOnly = sel('gg-failing').checked;
		return edges.filter(function (edge) {
			if (focus && edge.from !== focus && edge.to !== focus) {
				return false;
			}
			if (needle && edge.from.toLowerCase().indexOf(needle) < 0
					&& edge.to.toLowerCase().indexOf(needle) < 0) {
				return false;
			}
			if (edge.calls < minCalls) {
				return false;
			}
			return !failingOnly || edge.errors > 0;
		});
	}

	/** The nodes the visible edges refer to, with the totals recomputed over them. */
	function visibleNodes(shown) {
		var totals = {};
		shown.forEach(function (edge) {
			totals[edge.from] = (totals[edge.from] || 0) + edge.calls;
			totals[edge.to] = (totals[edge.to] || 0) + edge.calls;
		});
		return nodes.filter(function (node) {
			return Object.prototype.hasOwnProperty.call(totals, node.id);
		}).map(function (node) {
			return { id: node.id, kind: node.kind, calls: totals[node.id] };
		});
	}

	/**
	 * Node radius, from the calls it took part in. A square root rather than a linear
	 * scale: the area is what the eye compares, and a busy node would otherwise swallow
	 * the picture.
	 */
	function radius(calls, max) {
		if (max <= 0) {
			return 14;
		}
		return 12 + 26 * Math.sqrt(calls / max);
	}

	/** Edge colour, from grey to red as the share of failed calls grows. */
	function edgeColor(edge) {
		var rate = errorRate(edge);
		if (rate <= 0) {
			return '#7f8c9b';
		}
		return (rate >= 0.5) ? '#c0392b' : '#e08e0b';
	}

	function renderChart() {
		var shown = visibleEdges();
		var drawn = visibleNodes(shown);
		if (!drawn.length) {
			chart.clear();
			sel('gg-empty').style.display = '';
			sel('gg-legend').textContent = '';
			return;
		}
		sel('gg-empty').style.display = 'none';
		var maxNodeCalls = Math.max.apply(null, drawn.map(function (node) {
			return node.calls;
		}));
		var maxEdgeCalls = Math.max.apply(null, shown.map(function (edge) {
			return edge.calls;
		}));
		chart.setOption({
			tooltip: {
				formatter: function (params) {
					if (params.dataType === 'edge') {
						var edge = params.data.edge;
						return params.data.source + ' &rarr; ' + params.data.target
							+ (edge.routeId ? '<br>route: ' + edge.routeId : '')
							+ '<br>calls: ' + edge.calls
							+ '<br>5xx: ' + edge.errors
							+ ' (' + (errorRate(edge) * 100).toFixed(1) + '%)';
					}
					return params.data.name + '<br>' + params.data.kind.toLowerCase()
						+ '<br>calls: ' + params.data.calls;
				}
			},
			series: [{
				type: 'graph',
				// Scroll to zoom, drag to pan. Nodes stay draggable either way.
				roam: true,
				draggable: true,
				layout: frozen() && hasPositions(drawn) ? 'none' : 'force',
				force: { repulsion: 320, edgeLength: [90, 220], gravity: 0.08 },
				label: { show: true, position: 'right', formatter: '{b}' },
				edgeSymbol: ['none', 'arrow'],
				edgeSymbolSize: 9,
				emphasis: { focus: 'adjacency', lineStyle: { width: 6 } },
				data: drawn.map(function (node) {
					var point = positions[node.id];
					return {
						name: node.id,
						kind: node.kind,
						calls: node.calls,
						symbolSize: radius(node.calls, maxNodeCalls),
						x: point ? point.x : undefined,
						y: point ? point.y : undefined,
						fixed: !!point && frozen(),
						itemStyle: { color: node.kind === 'SERVICE' ? SERVICE_COLOR : CALLER_COLOR }
					};
				}),
				links: shown.map(function (edge) {
					return {
						source: edge.from,
						target: edge.to,
						edge: edge,
						// Two routes between the same pair are two edges; curving them
						// keeps the second one from hiding under the first.
						lineStyle: {
							width: 1 + 5 * (maxEdgeCalls > 0 ? edge.calls / maxEdgeCalls : 0),
							color: edgeColor(edge),
							curveness: 0.12,
							opacity: 0.9
						}
					};
				})
			}]
		}, true);
		sel('gg-legend').textContent = drawn.length + ' node' + (drawn.length > 1 ? 's' : '') + ', '
			+ shown.length + ' edge' + (shown.length > 1 ? 's' : '')
			+ ' — node size and arrow width are the number of calls, red is the share that failed.';
	}

	function hasPositions(drawn) {
		return drawn.every(function (node) {
			return positions[node.id];
		});
	}

	/**
	 * Keeps where the force layout put each node, so the next render can reuse it. Read
	 * after the layout settles rather than on every frame.
	 */
	function rememberPositions() {
		var series = chart.getModel && chart.getModel().getSeriesByIndex(0);
		if (!series || !series.getData) {
			return;
		}
		var data = series.getData();
		data.each(function (index) {
			var layout = data.getItemLayout(index);
			if (layout) {
				positions[data.getName(index)] = { x: layout[0], y: layout[1] };
			}
		});
	}

	function renderTable() {
		var shown = visibleEdges().slice();
		shown.sort(function (left, right) {
			var a = (sortKey === 'errorRate') ? errorRate(left) : left[sortKey];
			var b = (sortKey === 'errorRate') ? errorRate(right) : right[sortKey];
			if (a === b) {
				return 0;
			}
			if (typeof a === 'string' || typeof b === 'string') {
				return String(a || '').localeCompare(String(b || '')) * -sortDir;
			}
			return (a < b) ? -sortDir : sortDir;
		});
		var body = sel('gg-tbody');
		body.textContent = '';
		shown.forEach(function (edge) {
			var row = document.createElement('tr');
			[edge.from, edge.to, edge.routeId || '—'].forEach(function (value) {
				var cell = document.createElement('td');
				cell.textContent = value;
				row.appendChild(cell);
			});
			[edge.calls, edge.errors, (errorRate(edge) * 100).toFixed(1) + '%'].forEach(function (value) {
				var cell = document.createElement('td');
				cell.className = 'text-end';
				cell.textContent = value;
				row.appendChild(cell);
			});
			body.appendChild(row);
		});
		sel('gg-table-empty').style.display = shown.length ? 'none' : '';
	}

	/** Fills the focus list with the nodes of the graph, keeping the current selection. */
	function renderFocusOptions() {
		var focus = sel('gg-focus');
		var selected = focus.value;
		focus.textContent = '';
		var all = document.createElement('option');
		all.value = '';
		all.textContent = 'The whole graph';
		focus.appendChild(all);
		nodes.forEach(function (node) {
			var option = document.createElement('option');
			option.value = node.id;
			option.textContent = node.id;
			focus.appendChild(option);
		});
		focus.value = selected;
		if (focus.value !== selected) {
			focus.value = '';
		}
	}

	function renderKpis() {
		var services = nodes.filter(function (node) {
			return node.kind === 'SERVICE';
		}).length;
		var calls = edges.reduce(function (total, edge) {
			return total + edge.calls;
		}, 0);
		var errors = edges.reduce(function (total, edge) {
			return total + edge.errors;
		}, 0);
		sel('gg-kpi-services').textContent = services;
		sel('gg-kpi-callers').textContent = nodes.length - services;
		sel('gg-kpi-calls').textContent = calls;
		sel('gg-kpi-errors').textContent = errors;
	}

	function render() {
		renderKpis();
		renderChart();
		renderTable();
	}

	function load() {
		fetch(dataUrl, { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(function (json) {
				nodes = (json && json.nodes) || [];
				edges = (json && json.edges) || [];
				// What this graph covers: one instance, every instance through a
				// provider, or a tracing backend. Shown next to it so a missing edge is
				// never read as a call that did not happen.
				var coverage = sel('gg-coverage');
				if (coverage) {
					coverage.textContent = (json && json.coverage) ? json.coverage : '';
				}
				renderFocusOptions();
				render();
			})
			.catch(function () {
				nodes = [];
				edges = [];
				render();
			});
	}

	// Clicking a node focuses it; clicking the focused one goes back to the whole graph.
	chart.on('click', function (params) {
		if (params.dataType !== 'node') {
			return;
		}
		sel('gg-focus').value = (sel('gg-focus').value === params.data.name) ? '' : params.data.name;
		render();
	});
	chart.on('finished', function () {
		if (frozen()) {
			rememberPositions();
		}
	});

	['gg-focus', 'gg-search', 'gg-min-calls', 'gg-failing'].forEach(function (id) {
		sel(id).addEventListener('input', render);
		sel(id).addEventListener('change', render);
	});
	sel('gg-freeze').addEventListener('change', function () {
		if (!frozen()) {
			positions = {};
		}
		render();
	});
	sel('gg-refresh').addEventListener('click', load);
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

	['gg-focus', 'gg-search', 'gg-min-calls', 'gg-failing', 'gg-freeze'].forEach(function (id) {
		window.gatewayUi.remember(sel(id));
	});

	load();
})();
