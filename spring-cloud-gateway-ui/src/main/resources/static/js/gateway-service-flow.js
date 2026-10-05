/*
 * Flow view. The edges of the service graph laid out as they run — the callers that
 * reached the gateway on the left, the gateway in the middle, the services it reached on
 * the right — drawn as plain SVG rather than through the charting library: the layout is
 * three fixed columns, which a force layout would only have to be argued out of.
 *
 * It reads the same payload as the graph view, and polls. The picture is never rebuilt:
 * every box, every line and every figure on them is an element that is created once and
 * kept, so a poll moves numbers and colours rather than replacing the page under the
 * reader. What changes rank slides to its new row; what changes value counts up to it.
 *
 * Polling is what the travelling dots are for. Each poll is compared to the one before it,
 * and a hop that carried calls in between sends dots down its line — as many as the square
 * root of the calls, so a busy hop is a stream and a quiet one a single dot — coloured by
 * how those calls came back. A hop that carried nothing stays still: the picture only moves
 * when the gateway is carrying something, and the halo of the hub beats on the same rule.
 */
(function () {
	'use strict';

	var flowEl = document.getElementById('gf-flow');
	if (!flowEl) {
		return;
	}

	var SVG_NS = 'http://www.w3.org/2000/svg';
	var BOX_W = 230;
	var BOX_H = 74;
	// The strip across the top of a box, carrying what the box is and how much it took.
	var STRIP_H = 22;
	var ROW_GAP = 92;
	var PAD_Y = 18;
	var HUB_W = 250;
	var HUB_H = 112;
	var HUB_STRIP = 24;
	// The halo is drawn around the hub and an SVG clips at its own edge, so a picture of
	// two rows still has to be tall enough to hold it.
	var MIN_H = 360;
	var MIN_W = 760;

	var POLL_MS = 3000;
	// One leg of a call. The two run in order, so a dot leaving on the second one is still
	// travelling when the next poll lands: the streams of two polls overlap, which is what
	// keeps a busy gateway looking carried rather than strobed.
	var TRAVEL_MS = 1100;
	// A box that changes rank slides to its new row, and the lines follow it.
	var MOVE_MS = 420;
	var COUNT_MS = 700;
	// A burst of a thousand calls is still traffic on one hop, not a thousand dots to draw.
	var MAX_DOTS = 14;
	// And twenty hops bursting at once are not three hundred dots to move every frame.
	var MAX_PARTICLES = 220;

	var dataUrl = flowEl.getAttribute('data-url') || '/ui/service-graph/data';
	var edges = [];
	var readAt = 0;
	var timer = null;
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

	var svg = null;
	var layers = {};
	var nodes = {};
	var links = {};
	var hub = null;
	var halo = null;
	var rings = [];
	// What the halo is drawn at, which the height of the picture gives it: a flow of two
	// rows is shorter than the halo would like, and a ring cut by the edge of the SVG reads
	// as a drawing error rather than as a glow.
	var reach = 0;
	var particles = [];
	var counters = [];
	var move = { active: false, start: 0 };
	// The order each column is drawn in. See ordered().
	var order = { in: [], out: [] };
	var ticking = false;

	function sel(id) {
		return document.getElementById(id);
	}

	function num(value) {
		return Math.round(value).toLocaleString('en-US');
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

	/** The worst outcome a hop saw since the gateway started, which the bar on its box reports. */
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

	/* The error classes of a box, on their own line, with the ones that stayed at zero left out. */
	function detail(hop) {
		var failed = [];
		if (hop.clientErrors > 0) {
			failed.push(num(hop.clientErrors) + ' 4xx');
		}
		if (hop.errors > 0) {
			failed.push(num(hop.errors) + ' 5xx');
		}
		return failed.join(' · ');
	}

	/** The same figures on one line, for the tooltip a box and a hop carry. */
	function summary(hop) {
		var line = num(hop.calls) + ((hop.calls === 1) ? ' call' : ' calls');
		var failed = detail(hop);
		return failed ? (line + ' · ' + failed) : line;
	}

	/*
	 * SVG has no ellipsis: a name longer than its box is cut here, on a character budget
	 * taken from what the count drawn at the other end of the box leaves it, in the
	 * monospace the name is drawn in. The full name stays reachable through the <title> of
	 * the group.
	 */
	function fit(name, room) {
		var budget = Math.floor(room / 7.1);
		return (name.length <= budget) ? name : name.slice(0, Math.max(1, budget - 1)) + '…';
	}

	function el(name, attributes) {
		var node = document.createElementNS(SVG_NS, name);
		Object.keys(attributes).forEach(function (key) {
			node.setAttribute(key, attributes[key]);
		});
		return node;
	}

	function text(className, x, y, anchor) {
		var node = el('text', { x: x, y: y, class: className });
		if (anchor) {
			node.setAttribute('text-anchor', anchor);
		}
		return node;
	}

	/* ------------------------------------------------------------------ animation */

	/*
	 * One frame loop for the three things that move: the boxes sliding to a new rank, the
	 * figures counting up to a new value, and the dots travelling a hop. It runs while any
	 * of them has work and stops itself when none has — a flow nobody is sending traffic
	 * through costs no frames at all.
	 */
	function wake() {
		if (!ticking) {
			ticking = true;
			window.requestAnimationFrame(tick);
		}
	}

	function tick(now) {
		var busy = stepMove(now);
		busy = stepCounts(now) || busy;
		busy = stepParticles(now) || busy;
		if (busy) {
			window.requestAnimationFrame(tick);
		}
		else {
			ticking = false;
		}
	}

	function ease(t) {
		return (t < 0.5) ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
	}

	function lerp(from, to, k) {
		return from + (to - from) * k;
	}

	function stepMove(now) {
		if (!move.active) {
			return false;
		}
		var raw = Math.min(1, (now - move.start) / MOVE_MS);
		var k = ease(raw);
		Object.keys(nodes).forEach(function (key) {
			place(nodes[key], k);
		});
		Object.keys(links).forEach(function (key) {
			bend(links[key], k);
		});
		if (raw >= 1) {
			move.active = false;
		}
		return true;
	}

	function place(view, k) {
		view.at = [lerp(view.from[0], view.to[0], k), lerp(view.from[1], view.to[1], k)];
		view.group.setAttribute('transform', 'translate(' + view.at[0] + ' ' + view.at[1] + ')');
	}

	function bend(view, k) {
		var at = [];
		for (var index = 0; index < 8; index += 1) {
			at.push(lerp(view.from[index], view.to[index], k));
		}
		view.at = at;
		view.path.setAttribute('d', 'M' + at[0] + ' ' + at[1] + ' C' + at[2] + ' ' + at[3]
			+ ', ' + at[4] + ' ' + at[5] + ', ' + at[6] + ' ' + at[7]);
	}

	/*
	 * Every box and every line leaves where it is drawn and arrives where the new layout
	 * puts it. A view that has just been created is already there — it appears in place
	 * rather than flying in from the corner of the picture.
	 */
	function startMove(immediate) {
		var jump = immediate || still;
		Object.keys(nodes).forEach(function (key) {
			nodes[key].from = nodes[key].at;
		});
		Object.keys(links).forEach(function (key) {
			links[key].from = links[key].at;
		});
		if (jump) {
			move.active = false;
			Object.keys(nodes).forEach(function (key) {
				place(nodes[key], 1);
			});
			Object.keys(links).forEach(function (key) {
				bend(links[key], 1);
			});
			return;
		}
		move.active = true;
		move.start = window.performance.now();
		wake();
	}

	/*
	 * A figure counts from what it showed to what it now is. It is the one thing on the
	 * picture the reader is watching when traffic arrives, and a number that jumps is a
	 * number nobody saw change.
	 */
	function count(node, value) {
		// A figure nobody has read yet is written rather than counted up to: the boxes of a
		// flow opened on a gateway that has been up for a day would otherwise all run their
		// history past the reader before settling.
		if (node._gwValue === undefined) {
			node.textContent = num(value);
			node._gwValue = value;
			return false;
		}
		if (node._gwValue === value) {
			return false;
		}
		if (still) {
			node.textContent = num(value);
			node._gwValue = value;
			return true;
		}
		counters = counters.filter(function (entry) {
			return entry.node !== node;
		});
		counters.push({
			node: node, from: node._gwValue, to: value, start: window.performance.now()
		});
		node._gwValue = value;
		wake();
		return true;
	}

	function stepCounts(now) {
		if (!counters.length) {
			return false;
		}
		counters = counters.filter(function (entry) {
			var raw = Math.min(1, (now - entry.start) / COUNT_MS);
			entry.node.textContent = num(lerp(entry.from, entry.to, ease(raw)));
			return raw < 1;
		});
		return true;
	}

	/* The box lights up for as long as it takes to read that its figures moved. */
	function flash(view) {
		if (still || !view.flash.animate) {
			return;
		}
		view.flash.animate([{ opacity: 0 }, { opacity: 0.4 }, { opacity: 0 }],
			{ duration: 900, easing: 'ease-out' });
	}

	/* ------------------------------------------------------------------ the picture */

	/*
	 * The layers, in the order they are drawn: the halo behind everything, then the lines,
	 * then the dots riding them, then the boxes — a dot reaching a box goes under it rather
	 * than over its name.
	 *
	 * The rounded corners of a box are cut by one clip path for all of them rather than one
	 * each: a clip path carrying no units of its own is read in the coordinates of whatever
	 * uses it, and every box is the same box drawn somewhere else.
	 */
	function build() {
		svg = el('svg', { class: 'gw-flow-svg', role: 'img' });
		var defs = document.createElementNS(SVG_NS, 'defs');
		var glow = el('radialGradient', { id: 'gf-halo' });
		glow.appendChild(el('stop', { offset: '0%', class: 'gw-flow-halo-core' }));
		glow.appendChild(el('stop', { offset: '45%', class: 'gw-flow-halo-mid' }));
		glow.appendChild(el('stop', { offset: '100%', class: 'gw-flow-halo-edge' }));
		defs.appendChild(glow);
		defs.appendChild(clip('gf-box-clip', BOX_W, BOX_H, 12));
		defs.appendChild(clip('gf-hub-clip', HUB_W, HUB_H, 14));
		svg.appendChild(defs);
		halo = el('g', { class: 'gw-flow-halo' });
		rings = [el('circle', { cx: 0, cy: 0, class: 'gw-flow-glow' })];
		// The rings the hub wears at rest. The beat sends one more through them, so the
		// gateway reads as a centre even on a picture nothing is travelling.
		[1, 2, 3].forEach(function (index) {
			rings.push(el('circle', { cx: 0, cy: 0,
				class: 'gw-flow-steady gw-flow-steady-' + index }));
		});
		rings.forEach(function (ring) {
			halo.appendChild(ring);
		});
		svg.appendChild(halo);
		['links', 'dots', 'nodes'].forEach(function (name) {
			layers[name] = el('g', { class: 'gw-flow-layer' });
			svg.appendChild(layers[name]);
		});
		flowEl.appendChild(svg);
	}

	function clip(id, width, height, radius) {
		var node = el('clipPath', { id: id });
		node.appendChild(el('rect', { x: 0, y: 0, width: width, height: height, rx: radius }));
		return node;
	}

	/*
	 * A box. The strip across its top says what the endpoint is to the gateway and how much
	 * of the traffic went through it; under it are its name and the figure it is drawn for.
	 * Strip, border and figure are tinted by the worst outcome the endpoint has ever seen,
	 * which is what makes a failing one findable among twenty at a glance.
	 */
	function node(key) {
		var group = el('g', { class: 'gw-flow-node gw-flow-ok' });
		var title = document.createElementNS(SVG_NS, 'title');
		group.appendChild(title);
		var inside = el('g', { 'clip-path': 'url(#gf-box-clip)' });
		inside.appendChild(el('rect', { width: BOX_W, height: BOX_H, class: 'gw-flow-box' }));
		inside.appendChild(el('rect', { width: BOX_W, height: STRIP_H, class: 'gw-flow-strip' }));
		var flashed = el('rect', { width: BOX_W, height: BOX_H, class: 'gw-flow-flash', opacity: 0 });
		inside.appendChild(flashed);
		group.appendChild(inside);
		group.appendChild(el('rect', { x: 0.5, y: 0.5, width: BOX_W - 1, height: BOX_H - 1,
			rx: 12, class: 'gw-flow-border' }));
		var view = {
			group: group, title: title, flash: flashed,
			kicker: text('gw-flow-kicker', 14, 15),
			share: text('gw-flow-share', BOX_W - 14, 15, 'end'),
			name: text('gw-flow-name', 14, 46),
			figure: text('gw-flow-count', BOX_W - 14, 50, 'end'),
			line: text('gw-flow-detail', 14, 65),
			at: [0, 0], from: [0, 0], to: [0, 0]
		};
		['kicker', 'share', 'name', 'figure', 'line'].forEach(function (part) {
			group.appendChild(view[part]);
		});
		layers.nodes.appendChild(group);
		nodes[key] = view;
		return view;
	}

	function updateNode(view, hop, role, part, x, y, fresh) {
		var figure = num(hop.calls);
		var line = detail(hop);
		// A box with no error class to report has one line fewer, and what it does not use
		// of its height is given back to the two it does rather than left under them.
		var shift = line ? 0 : 5;
		view.group.setAttribute('class', 'gw-flow-node gw-flow-' + health(hop));
		view.title.textContent = hop.id + ' — ' + summary(hop);
		view.kicker.textContent = role;
		view.share.textContent = part;
		view.name.textContent = fit(hop.id, BOX_W - 38 - figure.length * 12.6);
		view.name.setAttribute('y', 46 + shift);
		view.figure.setAttribute('y', 50 + shift);
		view.line.textContent = line;
		view.to = [x, y];
		if (fresh) {
			view.at = view.to;
			view.from = view.to;
			place(view, 1);
		}
		if (count(view.figure, hop.calls) && !fresh) {
			flash(view);
		}
	}

	/** What a hop took of everything the picture holds, which its strip reports. */
	function share(hop, total) {
		if (!total.calls) {
			return '';
		}
		var part = Math.round(hop.calls * 100 / total.calls);
		return (part < 1 ? '<1' : part) + '%';
	}

	/*
	 * A hop, drawn from the edge of a box to the edge of the hub. Its colour is the one
	 * question the line answers: how the last calls it carried came back. Until a poll has
	 * seen traffic on it there is no last batch to report, so it starts on the history and
	 * moves to the traffic as soon as some arrives.
	 */
	function link(key) {
		var group = el('g', { class: 'gw-flow-link gw-flow-ok' });
		var title = document.createElementNS(SVG_NS, 'title');
		group.appendChild(title);
		var path = el('path', { class: 'gw-flow-edge', d: 'M0 0' });
		group.appendChild(path);
		layers.links.appendChild(group);
		var view = { group: group, title: title, path: path,
			at: [0, 0, 0, 0, 0, 0, 0, 0], from: [0, 0, 0, 0, 0, 0, 0, 0],
			to: [0, 0, 0, 0, 0, 0, 0, 0] };
		links[key] = view;
		return view;
	}

	function updateLink(view, key, hop, from, to, fresh) {
		var middle = (from[0] + to[0]) / 2;
		view.title.textContent = summary(hop);
		view.group.setAttribute('class', 'gw-flow-link gw-flow-' + (recent[key] || health(hop)));
		view.to = [from[0], from[1], middle, from[1], middle, to[1], to[0], to[1]];
		if (fresh) {
			view.at = view.to;
			view.from = view.to;
			bend(view, 1);
		}
	}

	/*
	 * The hub. It carries what the gateway actually carried, not what is drawn: the cut made
	 * by 'Show' hides quiet endpoints, it does not un-count their calls.
	 */
	function buildHub() {
		var group = el('g', { class: 'gw-flow-node gw-flow-hub' });
		var title = document.createElementNS(SVG_NS, 'title');
		group.appendChild(title);
		var inside = el('g', { 'clip-path': 'url(#gf-hub-clip)' });
		inside.appendChild(el('rect', { width: HUB_W, height: HUB_H, class: 'gw-flow-box' }));
		inside.appendChild(el('rect', { width: HUB_W, height: HUB_STRIP, class: 'gw-flow-strip' }));
		var flashed = el('rect', { width: HUB_W, height: HUB_H, class: 'gw-flow-flash', opacity: 0 });
		inside.appendChild(flashed);
		group.appendChild(inside);
		group.appendChild(el('rect', { x: 0.5, y: 0.5, width: HUB_W - 1, height: HUB_H - 1,
			rx: 14, class: 'gw-flow-border' }));
		var kicker = text('gw-flow-kicker', 16, 16);
		kicker.textContent = 'gateway';
		group.appendChild(kicker);
		var tag = text('gw-flow-share', HUB_W - 16, 16, 'end');
		group.appendChild(tag);
		var name = text('gw-flow-title', HUB_W / 2, 60, 'middle');
		name.textContent = 'Gateway';
		group.appendChild(name);
		var stats = ['calls', '4xx', '5xx'].map(function (label, index) {
			var x = HUB_W / 2 + (index - 1) * 74;
			var caption = text('gw-flow-stat-label', x, 84, 'middle');
			caption.textContent = label;
			group.appendChild(caption);
			var value = text('gw-flow-stat', x, 101, 'middle');
			group.appendChild(value);
			return value;
		});
		layers.nodes.appendChild(group);
		hub = { group: group, title: title, flash: flashed, tag: tag, stats: stats,
			at: [0, 0], from: [0, 0], to: [0, 0] };
		nodes.hub = hub;
	}

	function updateHub(total, x, y) {
		hub.title.textContent = 'Gateway — ' + summary(total);
		hub.to = [x, y];
		paint(hub.stats[1], total.clientErrors, 'warn');
		paint(hub.stats[2], total.errors, 'bad');
		var moved = count(hub.stats[0], total.calls);
		moved = count(hub.stats[1], total.clientErrors) || moved;
		moved = count(hub.stats[2], total.errors) || moved;
		if (moved) {
			flash(hub);
		}
		liveTag();
	}

	function paint(node, value, state) {
		node.setAttribute('class', 'gw-flow-stat gw-flow-' + (value > 0 ? state : 'zero'));
	}

	/** The strip of the hub says whether what is under it is still being refreshed. */
	function liveTag() {
		if (hub) {
			hub.tag.textContent = sel('gf-auto').checked ? 'live' : 'paused';
		}
	}

	/*
	 * The halo beats when the gateway carries something: a ring leaves the hub and fades on
	 * its way out, once per poll that saw traffic. A gateway nobody is calling keeps the
	 * steady glow and sends no ring, which is the rule the dots obey too.
	 */
	function beat() {
		if (still || !halo) {
			return;
		}
		var ring = el('circle', { cx: 0, cy: 0, r: reach * 0.36, class: 'gw-flow-ring' });
		halo.appendChild(ring);
		window.setTimeout(function () {
			if (ring.parentNode) {
				ring.parentNode.removeChild(ring);
			}
		}, 2000);
	}

	/* ------------------------------------------------------------------ the dots */

	function cubic(geometry, t) {
		var mt = 1 - t;
		var a = mt * mt * mt;
		var b = 3 * mt * mt * t;
		var c = 3 * mt * t * t;
		var d = t * t * t;
		return {
			x: a * geometry[0] + b * geometry[2] + c * geometry[4] + d * geometry[6],
			y: a * geometry[1] + b * geometry[3] + c * geometry[5] + d * geometry[7]
		};
	}

	/*
	 * The dots a hop sends for the calls it carried since the last poll: as many as the
	 * square root of them, so one call is one dot and four hundred are the fourteen the
	 * line can hold without becoming a solid bar. They are coloured by how those calls came
	 * back rather than by what the hop has seen since it started — a hop that failed an hour
	 * ago and is answering now sends green.
	 *
	 * Their starts are spread across the poll and their speeds jittered, which is what makes
	 * a stream of them read as traffic rather than as a volley. The outbound leg leaves when
	 * the inbound one has arrived: a call is counted once at each end — the same exchange
	 * feeds 'in:caller' and 'out:service' — so the two legs are the two halves of one
	 * journey, and running them together draws a gateway sending on what it has not received
	 * yet.
	 */
	function spawn(key, delta) {
		var view = links[key];
		if (!view || still || !delta) {
			return;
		}
		var outbound = key.indexOf('out:') === 0;
		var state = health(delta);
		var wanted = Math.min(MAX_DOTS, Math.max(1, Math.round(Math.sqrt(delta.calls) * 1.6)));
		var now = window.performance.now();
		for (var index = 0; index < wanted; index += 1) {
			if (particles.length >= MAX_PARTICLES) {
				return;
			}
			var dot = el('g', { class: 'gw-flow-particle gw-flow-' + state, visibility: 'hidden' });
			dot.appendChild(el('circle', { cx: 0, cy: 0, r: 6, class: 'gw-flow-particle-halo' }));
			dot.appendChild(el('circle', { cx: 0, cy: 0, r: 2.4, class: 'gw-flow-particle-core' }));
			layers.dots.appendChild(dot);
			particles.push({
				el: dot, view: view, waiting: true,
				start: now + (outbound ? TRAVEL_MS : 0)
					+ index * (POLL_MS * 0.6) / wanted + Math.random() * 120,
				duration: TRAVEL_MS * (0.85 + Math.random() * 0.35)
			});
		}
		wake();
	}

	function stepParticles(now) {
		if (!particles.length) {
			return false;
		}
		particles = particles.filter(function (dot) {
			if (dot.view.dead) {
				drop(dot);
				return false;
			}
			if (now < dot.start) {
				return true;
			}
			var t = (now - dot.start) / dot.duration;
			if (t >= 1) {
				drop(dot);
				return false;
			}
			if (dot.waiting) {
				dot.el.removeAttribute('visibility');
				dot.waiting = false;
			}
			var at = cubic(dot.view.at, t);
			dot.el.setAttribute('transform', 'translate(' + at.x + ' ' + at.y + ')');
			return true;
		});
		return particles.length > 0;
	}

	function drop(dot) {
		if (dot.el.parentNode) {
			dot.el.parentNode.removeChild(dot.el);
		}
	}

	/* ------------------------------------------------------------------ the layout */

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

	/*
	 * The order a column is drawn in: its ranking the first time an endpoint is drawn, and
	 * then that endpoint's row for as long as it stays on the picture.
	 *
	 * Re-ranking on every poll is what a cut by calls invites and what the picture cannot
	 * afford: two endpoints a few calls apart trade places every three seconds, and a box
	 * sliding through its neighbour is unreadable whatever it is carrying. The figures are
	 * what moves here; a row moves only when an endpoint arrives or leaves, which is a
	 * change worth looking at. Which endpoints are kept is still decided by calls — that is
	 * what 'Show' cuts on — this only decides where the kept ones sit.
	 */
	function ordered(list, side) {
		var seen = {};
		list.forEach(function (hop) {
			seen[hop.id] = hop;
		});
		var kept = order[side].filter(function (id) {
			return seen[id];
		});
		list.forEach(function (hop) {
			if (kept.indexOf(hop.id) < 0) {
				kept.push(hop.id);
			}
		});
		order[side] = kept;
		return kept.map(function (id) {
			return seen[id];
		});
	}

	/** The column of boxes and the hops joining it to the gateway. */
	function column(list, x, hubX, hubY, height, inbound, keep, total) {
		var role = inbound ? 'caller' : 'service';
		var first = (height - (list.length * ROW_GAP - (ROW_GAP - BOX_H))) / 2;
		list.forEach(function (hop, index) {
			var y = first + index * ROW_GAP;
			var key = (inbound ? 'in:' : 'out:') + hop.id;
			keep[key] = true;
			var fresh = !nodes[key];
			updateNode(nodes[key] || node(key), hop, role, share(hop, total), x, y, fresh);
			var fresher = !links[key];
			updateLink(links[key] || link(key), key, hop,
				inbound ? [x + BOX_W, y + BOX_H / 2] : [hubX + HUB_W, hubY + HUB_H / 2],
				inbound ? [hubX, hubY + HUB_H / 2] : [x, y + BOX_H / 2], fresher);
		});
	}

	/** Whatever the filters no longer keep leaves the picture, and its dots with it. */
	function prune(keep) {
		[nodes, links].forEach(function (registry) {
			Object.keys(registry).forEach(function (key) {
				if (key === 'hub' || keep[key]) {
					return;
				}
				registry[key].dead = true;
				registry[key].group.parentNode.removeChild(registry[key].group);
				delete registry[key];
			});
		});
	}

	function render(immediate) {
		var shown = visibleEdges();
		var allCallers = hops(shown, 'from');
		var allServices = hops(shown, 'to');
		var callers = ordered(top(allCallers), 'in');
		var services = ordered(top(allServices), 'out');
		var hidden = (allCallers.length - callers.length) + (allServices.length - services.length);
		renderKpis(shown, allCallers, allServices);
		if (!callers.length) {
			prune({});
			sel('gf-empty').style.display = '';
			sel('gf-legend').textContent = '';
			if (svg) {
				svg.style.display = 'none';
			}
			arrived = {};
			return;
		}
		sel('gf-empty').style.display = 'none';
		if (!svg) {
			build();
			buildHub();
		}
		svg.style.display = '';

		var width = Math.max(flowEl.clientWidth || 0, MIN_W);
		var rows = Math.max(callers.length, services.length);
		var height = Math.max(MIN_H, rows * ROW_GAP - (ROW_GAP - BOX_H) + PAD_Y * 2);
		svg.setAttribute('width', width);
		svg.setAttribute('height', height);
		svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
		var total = shown.reduce(function (sum, edge) {
			return {
				calls: sum.calls + edge.calls,
				clientErrors: sum.clientErrors + edge.clientErrors,
				errors: sum.errors + edge.errors
			};
		}, { calls: 0, clientErrors: 0, errors: 0 });
		var hubX = (width - HUB_W) / 2;
		var hubY = (height - HUB_H) / 2;
		halo.setAttribute('transform', 'translate(' + (width / 2) + ' ' + (height / 2) + ')');
		reach = Math.min(225, height / 2 - 2);
		[1, 0.52, 0.74, 0.95].forEach(function (part, index) {
			rings[index].setAttribute('r', reach * part);
		});

		var keep = {};
		column(callers, 0, hubX, hubY, height, true, keep, total);
		column(services, width - BOX_W, hubX, hubY, height, false, keep, total);
		prune(keep);
		updateHub(total, hubX, hubY);
		startMove(immediate === true);

		Object.keys(arrived).forEach(function (key) {
			spawn(key, arrived[key]);
		});
		if (Object.keys(arrived).length) {
			beat();
		}
		// Drawn once. A later redraw for a filter or a resize must not replay it.
		arrived = {};

		sel('gf-legend').textContent = callers.length + ' caller' + (callers.length > 1 ? 's' : '')
			+ ', ' + services.length + ' service' + (services.length > 1 ? 's' : '')
			+ (hidden ? ', ' + hidden + ' quieter one' + (hidden > 1 ? 's' : '') + ' not drawn' : '')
			+ ' — dots travel a hop that carried calls since the last poll, as many as its traffic'
			+ ' deserves. The line is how its last calls came back, the box at its end everything'
			+ ' that endpoint has carried: green answered, amber 4xx, red 5xx.';
	}

	function redraw() {
		render(false);
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
		count(sel('gf-kpi-callers'), callers.length);
		count(sel('gf-kpi-services'), services.length);
		count(sel('gf-kpi-calls'), calls);
		count(sel('gf-kpi-client-errors'), clientErrors);
		count(sel('gf-kpi-errors'), errors);
	}

	/* ------------------------------------------------------------------ the poll */

	function load() {
		fetch(dataUrl, { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(function (json) {
				edges = (json && json.edges) || [];
				sel('gf-coverage').textContent = (json && json.coverage) ? json.coverage : '';
				diff();
				readAt = Date.now();
				renderAge();
				redraw();
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
		liveTag();
		if (sel('gf-auto').checked) {
			timer = window.setInterval(load, POLL_MS);
		}
	}

	['gf-search', 'gf-top', 'gf-4xx', 'gf-5xx'].forEach(function (id) {
		sel(id).addEventListener('input', redraw);
		sel(id).addEventListener('change', redraw);
		window.gatewayUi.remember(sel(id));
	});
	window.gatewayUi.remember(sel('gf-auto'));
	sel('gf-auto').addEventListener('change', schedule);
	sel('gf-refresh').addEventListener('click', load);
	/*
	 * The layout is measured from the width of its container, so it is taken again when that
	 * width changes — the window, the expand button, the menu folding beside it. Nothing is
	 * re-fetched and nothing slides: the picture is laid out again at once, because a width
	 * dragged by a mouse would otherwise have every box chasing the pointer. The width only:
	 * the height follows the number of rows, so watching it would have each redraw order the
	 * next.
	 */
	if (window.ResizeObserver) {
		var drawnAt = 0;
		new ResizeObserver(function () {
			if (flowEl.clientWidth !== drawnAt) {
				drawnAt = flowEl.clientWidth;
				render(true);
			}
		}).observe(flowEl);
	}
	else {
		window.addEventListener('resize', function () {
			render(true);
		});
	}

	load();
	schedule();
	window.setInterval(renderAge, 1000);
})();
