/*
 * Flow view. The edges of the service graph laid out as they run — the callers that
 * reached the gateway on the left, the gateway in the middle, the services it reached on
 * the right — drawn as plain SVG rather than through the charting library: the layout is
 * three fixed columns, which a force layout would only have to be argued out of.
 *
 * It reads the same payload as the graph view. Unlike that view it does poll, and it can:
 * the layout is three fixed columns computed from the data, so a redraw puts every box back
 * exactly where it was. What moved the graph view under its reader was the force layout, not
 * the refresh.
 *
 * Polling is what the travelling dots are for. Each poll is compared to the one before it,
 * and a hop that carried calls in between sends a dot down its line, coloured by how those
 * calls came back. A quiet hop stays still: the picture only moves when the gateway is
 * carrying something.
 */
(function () {
	'use strict';

	var flowEl = document.getElementById('gf-flow');
	if (!flowEl) {
		return;
	}

	var SVG_NS = 'http://www.w3.org/2000/svg';
	var BOX_W = 210;
	var BOX_H = 46;
	var ROW_GAP = 62;
	var PAD_Y = 12;
	var HUB_W = 150;

	var POLL_MS = 3000;
	var TRAVEL_MS = 1400;
	// A burst of a thousand calls is still traffic on one hop, not a thousand dots to draw.
	var MAX_DOTS = 3;

	var dataUrl = flowEl.getAttribute('data-url') || '/ui/service-graph/data';
	var edges = [];
	var coverage = '';
	var readAt = 0;
	var timer = null;
	var pathSeq = 0;
	// What each hop had carried at the previous poll, and what it carried since. The second
	// is filled by a load and emptied by the render that draws it, so redrawing for a filter
	// or a resize never replays traffic that has already been shown.
	var carried = {};
	var arrived = {};
	// How the last calls a hop actually carried came back, which is what colours its line.
	// It survives a redraw: a hop that has gone quiet keeps the colour of its last traffic
	// rather than falling back to a history that can only ever get worse.
	var recent = {};
	var still = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

	function sel(id) {
		return document.getElementById(id);
	}

	/**
	 * The edges left after the filters: a name fragment matched on either endpoint, and
	 * the classes of error asked for. Same rule as the graph view — neither switch on
	 * means no filtering rather than nothing drawn, and both on keep an edge that saw
	 * either.
	 */
	function visibleEdges() {
		var needle = (sel('gf-search').value || '').trim().toLowerCase();
		var only4xx = sel('gf-4xx').checked;
		var only5xx = sel('gf-5xx').checked;
		return edges.filter(function (edge) {
			if (needle && edge.from.toLowerCase().indexOf(needle) < 0
					&& edge.to.toLowerCase().indexOf(needle) < 0) {
				return false;
			}
			if (!only4xx && !only5xx) {
				return true;
			}
			return (only4xx && edge.clientErrors > 0) || (only5xx && edge.errors > 0);
		});
	}

	/**
	 * One hop per endpoint, summed over the edges it takes part in. An endpoint that both
	 * calls and is called appears in each list: on this side of the gateway it reached
	 * something, on the other it was reached, and the two are different figures.
	 */
	function hops(shown, key) {
		var totals = {};
		var order = [];
		shown.forEach(function (edge) {
			var id = edge[key];
			if (!totals[id]) {
				totals[id] = { id: id, calls: 0, clientErrors: 0, errors: 0 };
				order.push(id);
			}
			totals[id].calls += edge.calls;
			totals[id].clientErrors += edge.clientErrors;
			totals[id].errors += edge.errors;
		});
		return order.map(function (id) {
			return totals[id];
		}).sort(function (left, right) {
			return right.calls - left.calls;
		});
	}

	/** The worst outcome a hop saw since the gateway started, which its resting dot reports. */
	function health(hop) {
		if (hop.errors > 0) {
			return 'bad';
		}
		return (hop.clientErrors > 0) ? 'warn' : 'ok';
	}

	/**
	 * What every hop has carried, against what it had carried at the previous poll. The
	 * difference is what travels the line.
	 *
	 * A count that went down is a gateway that restarted its counters, not calls that
	 * un-happened: the hop is re-based on the new figure and sends nothing, or the whole
	 * history would travel the line at once.
	 */
	function diff() {
		var totals = {};
		edges.forEach(function (edge) {
			add(totals, 'in:' + edge.from, edge);
			add(totals, 'out:' + edge.to, edge);
		});
		var first = !readAt;
		Object.keys(totals).forEach(function (key) {
			var now = totals[key];
			var before = carried[key];
			if (before && now.calls > before.calls && !first) {
				var delta = {
					calls: now.calls - before.calls,
					clientErrors: now.clientErrors - before.clientErrors,
					errors: now.errors - before.errors
				};
				arrived[key] = delta;
				// The line follows the traffic, not the history: a batch that came back
				// clean puts it back to green, which is the only way a colour built on
				// counters that never go down can mean anything after an hour.
				recent[key] = health(delta);
			}
		});
		carried = totals;
	}

	function add(totals, key, edge) {
		if (!totals[key]) {
			totals[key] = { calls: 0, clientErrors: 0, errors: 0 };
		}
		totals[key].calls += edge.calls;
		totals[key].clientErrors += edge.clientErrors;
		totals[key].errors += edge.errors;
	}

	/** The figures under the name of a box, with the classes that stayed at zero left out. */
	function caption(hop) {
		var parts = [hop.calls + ((hop.calls === 1) ? ' call' : ' calls')];
		if (hop.clientErrors > 0) {
			parts.push(hop.clientErrors + ' 4xx');
		}
		if (hop.errors > 0) {
			parts.push(hop.errors + ' 5xx');
		}
		return parts.join(' · ');
	}

	/*
	 * SVG has no ellipsis: a name longer than its box is cut here, on a character budget
	 * taken from the width of the box in the monospace it is drawn in. The full name stays
	 * reachable through the <title> of the group.
	 */
	function fit(name, width) {
		var budget = Math.floor((width - 24) / 7.1);
		return (name.length <= budget) ? name : name.slice(0, Math.max(1, budget - 1)) + '…';
	}

	function el(name, attributes) {
		var node = document.createElementNS(SVG_NS, name);
		Object.keys(attributes).forEach(function (key) {
			node.setAttribute(key, attributes[key]);
		});
		return node;
	}

	function box(hop, x, y, width, hub) {
		var group = el('g', { class: hub ? 'gw-flow-node gw-flow-hub' : 'gw-flow-node' });
		var title = document.createElementNS(SVG_NS, 'title');
		title.textContent = hop.id + ' — ' + caption(hop);
		group.appendChild(title);
		group.appendChild(el('rect', { x: x, y: y, width: width, height: BOX_H, rx: 10 }));
		var name = el('text', { x: x + 12, y: y + 20, class: 'gw-flow-name' });
		name.textContent = fit(hop.id, width);
		group.appendChild(name);
		var detail = el('text', { x: x + 12, y: y + 36, class: 'gw-flow-detail' });
		detail.textContent = caption(hop);
		group.appendChild(detail);
		return group;
	}

	/*
	 * A hop, drawn from the right edge of one box to the left edge of the next.
	 *
	 * The dot sits at the end that is not the gateway: every hop meets the gateway at the
	 * same point, so a dot placed there would be one dot with every other one underneath
	 * it. At the endpoint end there is exactly one per hop, which is what makes a bad hop
	 * findable among twenty.
	 */
	function link(fromX, fromY, toX, toY, hop, inbound, key) {
		// The line reports the last calls the hop carried, the resting dot everything it has
		// carried. Until a poll has seen traffic on it there is no last batch to report, so
		// the line starts on the history and moves to the traffic as soon as some arrives.
		var group = el('g', { class: 'gw-flow-link gw-flow-' + (recent[key] || health(hop)) });
		var title = document.createElementNS(SVG_NS, 'title');
		title.textContent = caption(hop);
		group.appendChild(title);
		var middle = (fromX + toX) / 2;
		var dotX = inbound ? fromX + 12 : toX - 12;
		var dotY = inbound ? fromY : toY;
		var pathId = 'gf-path-' + (pathSeq += 1);
		group.appendChild(el('path', {
			id: pathId,
			d: 'M' + fromX + ' ' + fromY + ' C' + middle + ' ' + fromY + ', ' + middle + ' ' + toY
				+ ', ' + toX + ' ' + toY,
			class: 'gw-flow-edge'
		}));
		group.appendChild(el('circle', {
			cx: dotX, cy: dotY, r: 5, class: 'gw-flow-dot gw-flow-rest-' + health(hop)
		}));
		travel(group, pathId, arrived[key]);
		return group;
	}

	/**
	 * Sends one dot per new call down the hop, up to the maximum, coloured by how those
	 * calls came back rather than by what the hop has seen since it started: a hop that
	 * failed an hour ago and is answering now sends green.
	 *
	 * The dot is removed once it has arrived, and its motion is frozen rather than removed
	 * on the way out: a released motion puts the element back at its own coordinates, which
	 * for a circle carrying no centre is the top-left corner of the picture. Between the end
	 * of the travel and the removal that is a dot blinking outside the flow.
	 */
	function travel(group, pathId, delta) {
		if (!delta || still) {
			return;
		}
		var count = Math.min(MAX_DOTS, delta.calls);
		for (var index = 0; index < count; index += 1) {
			sendOne(group, pathId, health(delta), index);
		}
	}

	/*
	 * Several dots on one hop are spread by giving each a longer journey than the one
	 * before, never by delaying its start: a motion that has not begun leaves its element
	 * at its own coordinates, and a circle drawn at the origin of the picture is a dot
	 * blinking in the top-left corner until its turn comes.
	 */
	function sendOne(group, pathId, state, index) {
		var duration = TRAVEL_MS + index * 260;
		var dot = el('circle', { r: 5, class: 'gw-flow-travelling gw-flow-' + state });
		var motion = el('animateMotion', { dur: (duration / 1000) + 's', fill: 'freeze' });
		motion.appendChild(el('mpath', { href: '#' + pathId }));
		dot.appendChild(motion);
		group.appendChild(dot);
		window.setTimeout(function () {
			if (dot.parentNode) {
				dot.parentNode.removeChild(dot);
			}
		}, duration + 60);
	}

	/** The column of boxes and the hops joining it to the gateway. */
	function column(svg, list, x, hubX, hubY, width, height, inbound) {
		var top = (height - (list.length * ROW_GAP - (ROW_GAP - BOX_H))) / 2;
		list.forEach(function (hop, index) {
			var y = top + index * ROW_GAP;
			var key = (inbound ? 'in:' : 'out:') + hop.id;
			svg.appendChild(inbound
				? link(x + width, y + BOX_H / 2, hubX, hubY, hop, true, key)
				: link(hubX + HUB_W, hubY, x - 12, y + BOX_H / 2, hop, false, key));
			svg.appendChild(box(hop, x, y, width, false));
		});
	}

	/*
	 * The busiest endpoints of a column, and how many were left out.
	 *
	 * A gateway fronting a hundred services would otherwise stack a hundred boxes down the
	 * page — six thousand pixels of picture nobody scrolls through, and no more readable
	 * than no picture at all. The cut is by calls, so what is dropped is always the quiet
	 * end of the column; the legend says how much of it was dropped, and 'Everything' puts
	 * it back.
	 */
	function top(list) {
		var limit = parseInt(sel('gf-top').value, 10) || 0;
		return (limit > 0 && list.length > limit) ? list.slice(0, limit) : list;
	}

	function render() {
		var shown = visibleEdges();
		var allCallers = hops(shown, 'from');
		var allServices = hops(shown, 'to');
		var callers = top(allCallers);
		var services = top(allServices);
		var hidden = (allCallers.length - callers.length) + (allServices.length - services.length);
		flowEl.textContent = '';
		if (!callers.length) {
			sel('gf-empty').style.display = '';
			sel('gf-legend').textContent = '';
			renderKpis(shown, allCallers, allServices);
			arrived = {};
			return;
		}
		sel('gf-empty').style.display = 'none';

		var width = Math.max(flowEl.clientWidth || 0, 720);
		var rows = Math.max(callers.length, services.length);
		var height = rows * ROW_GAP - (ROW_GAP - BOX_H) + PAD_Y * 2;
		var svg = el('svg', {
			class: 'gw-flow-svg', width: width, height: height,
			viewBox: '0 0 ' + width + ' ' + height, role: 'img'
		});
		var hubX = (width - HUB_W) / 2;
		var hubY = height / 2;
		// The hub carries what the gateway actually carried, not what is drawn: the cut
		// above hides quiet endpoints, it does not un-count their calls.
		var total = shown.reduce(function (sum, edge) {
			return {
				id: 'Gateway',
				calls: sum.calls + edge.calls,
				clientErrors: sum.clientErrors + edge.clientErrors,
				errors: sum.errors + edge.errors
			};
		}, { id: 'Gateway', calls: 0, clientErrors: 0, errors: 0 });

		column(svg, callers, 0, hubX, hubY, BOX_W, height, true);
		column(svg, services, width - BOX_W, hubX, hubY, BOX_W, height, false);
		svg.appendChild(box(total, hubX, hubY - BOX_H / 2, HUB_W, true));
		flowEl.appendChild(svg);

		sel('gf-legend').textContent = callers.length + ' caller' + (callers.length > 1 ? 's' : '')
			+ ', ' + services.length + ' service' + (services.length > 1 ? 's' : '')
			+ (hidden ? ', ' + hidden + ' quieter one' + (hidden > 1 ? 's' : '') + ' not drawn' : '')
			+ ' — a dot travels a hop that carried calls since the last poll. The line is how its last'
			+ ' calls came back, the dot at rest everything it has carried: green answered, amber 4xx,'
			+ ' red 5xx.';
		renderKpis(shown, allCallers, allServices);
		// Drawn once. A later redraw for a filter or a resize must not replay it.
		arrived = {};
	}

	function renderKpis(shown, callers, services) {
		var calls = 0;
		var clientErrors = 0;
		var errors = 0;
		shown.forEach(function (edge) {
			calls += edge.calls;
			clientErrors += edge.clientErrors;
			errors += edge.errors;
		});
		sel('gf-kpi-callers').textContent = callers.length;
		sel('gf-kpi-services').textContent = services.length;
		sel('gf-kpi-calls').textContent = calls;
		sel('gf-kpi-client-errors').textContent = clientErrors;
		sel('gf-kpi-errors').textContent = errors;
	}

	function load() {
		fetch(dataUrl, { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(function (json) {
				edges = (json && json.edges) || [];
				coverage = (json && json.coverage) ? json.coverage : '';
				sel('gf-coverage').textContent = coverage;
				diff();
				readAt = Date.now();
				renderAge();
				render();
			})
			.catch(function () {
				// The age is left where it was: a failed poll makes the picture on screen
				// older, it does not make it younger.
				sel('gf-coverage').textContent = 'Could not read the flow.';
			});
	}

	function renderAge() {
		if (!readAt) {
			return;
		}
		var seconds = Math.round((Date.now() - readAt) / 1000);
		sel('gf-age').textContent = (seconds < 5) ? 'just now' : seconds + 's ago';
	}

	function schedule() {
		if (timer) {
			window.clearInterval(timer);
			timer = null;
		}
		if (sel('gf-auto').checked) {
			timer = window.setInterval(load, POLL_MS);
		}
	}

	['gf-search', 'gf-top', 'gf-4xx', 'gf-5xx'].forEach(function (id) {
		sel(id).addEventListener('input', render);
		sel(id).addEventListener('change', render);
		window.gatewayUi.remember(sel(id));
	});
	window.gatewayUi.remember(sel('gf-auto'));
	sel('gf-auto').addEventListener('change', schedule);
	sel('gf-refresh').addEventListener('click', load);
	// The layout is measured from the width of its container, so it is taken again when
	// that width changes. Nothing is re-fetched: the picture is redrawn from what is held.
	window.addEventListener('resize', render);

	load();
	schedule();
	window.setInterval(renderAge, 1000);
})();
