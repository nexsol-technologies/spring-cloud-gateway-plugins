/*
 * Introspection views: what this gateway is made of, read from its Actuator endpoints.
 *
 * One script for the six of them. They share a page, a filter and an instance selector,
 * and differ only in how a payload is turned into rows — so what varies is a renderer per
 * view id, and nothing else.
 *
 * Everything is drawn through the DOM rather than assembled as HTML: these payloads carry
 * bean names, class names, patterns and property values straight out of a running
 * application, and a view that concatenated them into innerHTML would execute whatever a
 * property happened to contain.
 */
(function () {
	'use strict';

	var body = document.getElementById('gi-body');
	if (!body) {
		return;
	}

	var viewId = body.dataset.view;
	var dataUrl = body.dataset.url;
	var writeUrl = body.dataset.writeUrl;
	var writable = body.dataset.writable === 'true';
	var LEVELS = ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'];

	var payload = null;

	function sel(id) {
		return document.getElementById(id);
	}

	function instance() {
		var picker = sel('gi-instance');
		return picker ? picker.value : '';
	}

	/**
	 * The instances a change is about to reach, for the sentence under the table. Without a
	 * picker there is one instance and nothing to say.
	 */
	function scope() {
		var picker = sel('gi-instance');
		if (!picker) {
			return '';
		}
		var chosen = picker.options[picker.selectedIndex];
		return chosen ? chosen.textContent : '';
	}

	function needle() {
		return (sel('gi-search').value || '').trim().toLowerCase();
	}

	function matches(text) {
		var fragment = needle();
		return !fragment || String(text).toLowerCase().indexOf(fragment) >= 0;
	}

	/** A table with the given headings, ready for rows. */
	function table(headings) {
		var element = document.createElement('table');
		element.className = 'table table-sm align-middle mb-0 gw-insights-table';
		var head = document.createElement('thead');
		var row = document.createElement('tr');
		row.className = 'text-secondary small';
		headings.forEach(function (heading) {
			var cell = document.createElement('th');
			cell.textContent = heading;
			row.appendChild(cell);
		});
		head.appendChild(row);
		element.appendChild(head);
		element.appendChild(document.createElement('tbody'));
		return element;
	}

	function row(tbody, values) {
		var line = document.createElement('tr');
		values.forEach(function (value) {
			var cell = document.createElement('td');
			if (value instanceof Node) {
				cell.appendChild(value);
			}
			else {
				cell.textContent = (value === null || value === undefined) ? '—' : String(value);
			}
			line.appendChild(cell);
		});
		tbody.appendChild(line);
		return line;
	}

	/*
	 * A token, cut with an ellipsis rather than wrapped: these are logger names, bean names
	 * and property keys, and one of them wrapped over three lines pushes every other column
	 * off the screen. The full value stays on the title.
	 */
	function code(text) {
		var element = document.createElement('code');
		element.className = 'gw-insights-code';
		element.textContent = text;
		element.title = text;
		return element;
	}

	/** A value of any shape, rendered so an object is readable rather than "[object Object]". */
	function readable(value) {
		if (value === null || value === undefined) {
			return '—';
		}
		if (typeof value === 'object') {
			return JSON.stringify(value);
		}
		return String(value);
	}

	/** The application context of a payload that carries one context per application. */
	function firstContext(contexts) {
		if (!contexts) {
			return null;
		}
		var names = Object.keys(contexts);
		return names.length ? contexts[names[0]] : null;
	}

	/* Renderers ------------------------------------------------------------ */

	/**
	 * configprops: a folding group per prefix, and the properties it bound under it.
	 *
	 * A prefix is the unit a reader looks for — nobody scans four hundred rows to find
	 * what the audit plugin was configured with — so the prefix is the heading and its
	 * properties fold under it. Read-only throughout: this is what the gateway bound at
	 * start-up, not a form.
	 *
	 * Folding is a <details>, so it costs no script and keeps its keyboard behaviour. A
	 * filter opens every group it left standing: a match hidden inside a folded group is
	 * a match the reader never sees.
	 */
	function renderConfiguration(json) {
		var context = firstContext(json.contexts);
		var beans = (context && context.beans) || {};
		var tree = document.createElement('div');
		tree.className = 'gw-insights-tree';
		var filtered = !!needle();
		var shown = 0;
		var groups = 0;
		Object.keys(beans).sort().forEach(function (name) {
			var bean = beans[name];
			var prefix = bean.prefix || name;
			var properties = bean.properties || {};
			var kept = Object.keys(properties).sort().filter(function (key) {
				return matches(prefix + '.' + key) || matches(readable(properties[key]));
			});
			if (!kept.length) {
				return;
			}
			tree.appendChild(group(prefix, properties, kept, filtered));
			shown += kept.length;
			groups += 1;
		});
		return { element: tree, count: shown, unit: 'property', plural: 'properties',
			note: groups + (groups === 1 ? ' prefix' : ' prefixes') };
	}

	/** One prefix and the properties bound under it. */
	function group(prefix, properties, kept, open) {
		var details = document.createElement('details');
		details.className = 'gw-insights-group';
		details.open = open;
		var summary = document.createElement('summary');
		summary.appendChild(code(prefix));
		var count = document.createElement('span');
		count.className = 'gw-insights-count';
		count.textContent = kept.length;
		summary.appendChild(count);
		details.appendChild(summary);
		var list = document.createElement('dl');
		list.className = 'gw-insights-props';
		kept.forEach(function (key) {
			var term = document.createElement('dt');
			term.textContent = key;
			term.title = prefix + '.' + key;
			var value = document.createElement('dd');
			value.textContent = readable(properties[key]);
			list.appendChild(term);
			list.appendChild(value);
		});
		details.appendChild(list);
		return details;
	}

	/**
	 * env: one row per property, with the source it was taken from and the sources it also
	 * appears in. That is the diff — a value is whatever the first source to carry it says,
	 * and knowing which one that was is the whole question when a profile misbehaves.
	 */
	function renderProfileDiff(json) {
		var sources = json.propertySources || [];
		var seen = {};
		var order = [];
		sources.forEach(function (source) {
			var properties = source.properties || {};
			Object.keys(properties).forEach(function (key) {
				if (!seen[key]) {
					seen[key] = { winner: source.name, value: properties[key].value, others: [] };
					order.push(key);
				}
				else {
					seen[key].others.push(source.name);
				}
			});
		});
		var element = table(['Property', 'Value', 'From', 'Also in']);
		var tbody = element.querySelector('tbody');
		var shown = 0;
		order.sort().forEach(function (key) {
			var entry = seen[key];
			if (!matches(key) && !matches(readable(entry.value))) {
				return;
			}
			var overridden = entry.others.length;
			var line = row(tbody, [code(key), readable(entry.value), entry.winner,
				overridden ? overridden + ' other' + (overridden > 1 ? 's' : '') : '—']);
			// A property several sources carry is the one worth spotting: the value in use
			// is not the only one declared.
			if (overridden) {
				line.classList.add('gw-insights-shadowed');
				line.title = 'Also declared in: ' + entry.others.join(', ');
			}
			shown += 1;
		});
		var profiles = (json.activeProfiles || []).join(', ');
		return { element: element, count: shown, unit: 'property', plural: 'properties',
			note: profiles ? 'Active profiles: ' + profiles : 'No profile is active.' };
	}

	/** loggers: one row per configured logger, with the level it records at. */
	function renderLoggers(json) {
		var loggers = json.loggers || {};
		// What the application declared, which the gateway itself no longer knows once a
		// level has been changed: the console remembers it and hands it back here.
		var baseline = json._baseline || {};
		var element = table(['Logger', 'Configured', 'Effective']);
		var tbody = element.querySelector('tbody');
		var shown = 0;
		Object.keys(loggers).sort().forEach(function (name) {
			if (!matches(name)) {
				return;
			}
			var logger = loggers[name];
			// Read across the fleet, a logger the instances disagree on has no one level to
			// show. Saying so is the point: a button drawn as pressed would claim a level
			// most of the fleet is not recording at.
			var effective = logger.mixed ? mixedTag() : (logger.effectiveLevel || '—');
			row(tbody, [code(name),
				writable ? levelButtons(name, logger.configuredLevel, baseline[name] || null)
					: (logger.mixed ? mixedTag() : (logger.configuredLevel || '—')),
				effective]);
			shown += 1;
		});
		var reach = writable && scope() ? ' · changes apply to ' + scope() : '';
		return { element: element, count: shown, unit: 'logger', note: reach ? reach.slice(3) : null };
	}

	/**
	 * The control that sets a level, only built for a principal allowed to use it: one
	 * button per level, the one in force pressed, and a last one putting the logger back to
	 * what the application declared.
	 *
	 * That last one restores rather than clears. Posting no level to Actuator drops the
	 * logger to whatever its parent says, so a logger the configuration declared `debug`
	 * would come back `info` and the declared setting would be gone until a restart. It
	 * therefore re-posts the level the console first read, and only clears the logger when
	 * there was none to begin with.
	 *
	 * Buttons rather than a list, because the levels are six and ordered: which one is in
	 * force is then read without opening anything, and moving one notch quieter is one
	 * click rather than three.
	 */
	function levelButtons(name, current, original) {
		var group = el2('div', 'btn-group btn-group-sm gw-insights-levels');
		group.setAttribute('role', 'group');
		group.setAttribute('aria-label', 'Level of ' + name);
		LEVELS.forEach(function (level) {
			var button = el2('button', 'btn btn-outline-secondary'
				+ ((current === level) ? ' gw-insights-level-on' : ''));
			button.type = 'button';
			button.textContent = level;
			button.setAttribute('aria-pressed', String(current === level));
			button.addEventListener('click', function () {
				setLevel(name, level, group);
			});
			group.appendChild(button);
		});
		var reset = el2('button', 'btn btn-outline-secondary gw-insights-level-reset');
		reset.type = 'button';
		reset.textContent = '↺';
		reset.title = original ? ('Back to ' + original + ', the level the application declared')
			: 'Back to no level of its own, the way the application left it';
		// Nothing to undo on a logger already sitting where the application left it.
		reset.disabled = (current || null) === original;
		reset.addEventListener('click', function () {
			setLevel(name, original || '', group);
		});
		group.appendChild(reset);
		return group;
	}

	function el2(name, className) {
		var node = document.createElement(name);
		node.className = className;
		return node;
	}

	/** Every button of a control, while the level it asked for is being set. */
	function disable(control, state) {
		Array.prototype.forEach.call(control.querySelectorAll('button'), function (button) {
			button.disabled = state;
		});
	}

	function setLevel(name, level, control) {
		disable(control, true);
		var headers = { 'Content-Type': 'application/json' };
		var token = document.querySelector('meta[name="_csrf"]');
		var header = document.querySelector('meta[name="_csrf_header"]');
		if (token && header) {
			headers[header.content] = token.content;
		}
		fetch(writeUrl + '/' + encodeURIComponent(name) + query(), {
			method: 'POST', headers: headers,
			body: JSON.stringify({ configuredLevel: level || null })
		}).then(function (response) {
			if (!response.ok) {
				throw new Error('rejected');
			}
			// Silently: setting a level changes the effective level of every logger under
			// the one that was set, so the table is read again rather than patched — but
			// the reader is left where they were, on the row they just used.
			load(true);
		}).catch(function () {
			disable(control, false);
			fail('The level could not be set on this instance.');
		});
	}

	/** beans: one row per bean, with what it is and what it was wired from. */
	function renderBeans(json) {
		var context = firstContext(json.contexts);
		var beans = (context && context.beans) || {};
		var element = table(['Bean', 'Type', 'Scope', 'Wired from']);
		var tbody = element.querySelector('tbody');
		var shown = 0;
		Object.keys(beans).sort().forEach(function (name) {
			var bean = beans[name];
			if (!matches(name) && !matches(bean.type || '')) {
				return;
			}
			var dependencies = bean.dependencies || [];
			row(tbody, [code(name), bean.type || '—', bean.scope || '—',
				dependencies.length ? dependencies.join(', ') : '—']);
			shown += 1;
		});
		return { element: element, count: shown, unit: 'bean' };
	}

	/**
	 * conditions: why an auto-configuration was applied, or was not.
	 *
	 * Stacked rather than tabulated. The three things one entry carries — the outcome, the
	 * configuration, and the condition with the sentence explaining it — are of very
	 * different lengths, and a message runs far longer than any column can be: laid out in
	 * columns the report is read sideways, which for a thousand entries is unreadable.
	 * Stacked, the outcome and the name stay aligned down the left and the sentence wraps
	 * under them.
	 *
	 * A report of a full gateway runs to a thousand entries and the two outcomes answer
	 * different questions — "why is this on" and "why is this not" — so the outcome is
	 * narrowed on rather than scrolled past.
	 */
	function renderConditions(json) {
		var context = firstContext(json.contexts);
		var positive = (context && context.positiveMatches) || {};
		var negative = (context && context.negativeMatches) || {};
		var outcome = sel('gi-outcome') ? sel('gi-outcome').value : '';
		var list = document.createElement('div');
		list.className = 'gw-conditions';
		var shown = 0;
		if (outcome !== 'not-matched') {
			Object.keys(positive).sort().forEach(function (name) {
				(positive[name] || []).forEach(function (match) {
					if (!keeps(name, match)) {
						return;
					}
					list.appendChild(condition('MATCH', 'ok', name, match));
					shown += 1;
				});
			});
		}
		if (outcome !== 'matched') {
			Object.keys(negative).sort().forEach(function (name) {
				var entry = negative[name] || {};
				(entry.notMatched || []).forEach(function (match) {
					if (!keeps(name, match)) {
						return;
					}
					list.appendChild(condition('NO MATCH', 'no', name, match));
					shown += 1;
				});
			});
		}
		return { element: list, count: shown, unit: 'condition' };
	}

	/** The filter reaches the sentence too: the reason is often what is being looked for. */
	function keeps(name, match) {
		return matches(name) || matches(match.condition || '') || matches(match.message || '');
	}

	/** One entry: its outcome, the configuration it is about, and why. */
	function condition(label, state, name, match) {
		var entry = el2('div', 'gw-condition');
		entry.appendChild(badge(label, state));
		var detail = el2('div', 'gw-condition-body');
		var title = el2('div', 'gw-condition-name');
		title.textContent = name;
		detail.appendChild(title);
		if (match.condition) {
			var kind = el2('div', 'gw-condition-kind');
			kind.textContent = match.condition;
			detail.appendChild(kind);
		}
		var why = el2('div', 'gw-condition-why');
		why.textContent = match.message || '—';
		detail.appendChild(why);
		entry.appendChild(detail);
		return entry;
	}

	/** What a logger reads as when the instances of the fleet do not agree on it. */
	function mixedTag() {
		return badge('mixed', 'warn');
	}

	function badge(text, state) {
		var element = document.createElement('span');
		element.className = 'badge gw-insights-badge gw-insights-' + state;
		element.textContent = text;
		return element;
	}

	/**
	 * mappings: the routes and handlers this gateway answers on.
	 *
	 * The payload nests by area and then by the bean of that area — `dispatcherHandlers`
	 * holds a `webHandler`, which is the list — and a servlet application nests one level
	 * less. Both shapes are walked rather than one assumed, or the view comes back empty on
	 * whichever it was not written for.
	 */
	function renderMappings(json) {
		var context = firstContext(json.contexts);
		var mappings = (context && context.mappings) || {};
		var element = table(['Where', 'Predicate', 'Handler']);
		var tbody = element.querySelector('tbody');
		var shown = 0;
		Object.keys(mappings).forEach(function (area) {
			lists(mappings[area]).forEach(function (entries) {
				entries.forEach(function (entry) {
					var predicate = entry.predicate || entry.handler || '';
					var handler = entry.handler || '—';
					if (!matches(predicate) && !matches(handler)) {
						return;
					}
					row(tbody, [area, code(predicate), handler]);
					shown += 1;
				});
			});
		});
		return { element: element, count: shown, unit: 'mapping' };
	}

	/** The arrays held by a node, whether it is one itself or an object of them. */
	function lists(node) {
		if (Array.isArray(node)) {
			return [node];
		}
		if (!node || typeof node !== 'object') {
			return [];
		}
		return Object.keys(node).map(function (key) {
			return node[key];
		}).filter(Array.isArray);
	}

	var RENDERERS = {
		'configuration': renderConfiguration,
		'profile-diff': renderProfileDiff,
		'loggers': renderLoggers,
		'beans': renderBeans,
		'conditions': renderConditions,
		'mappings': renderMappings
	};

	/* Page ----------------------------------------------------------------- */

	function fail(message) {
		var error = sel('gi-error');
		error.textContent = message;
		error.style.display = '';
	}

	/** Whether the view is reading or drawing, which the page says rather than sitting blank. */
	function busy(state) {
		sel('gi-loading').style.display = state ? '' : 'none';
		if (state) {
			body.textContent = '';
			sel('gi-empty').style.display = 'none';
			sel('gi-count').textContent = '';
		}
	}

	/*
	 * Runs the work after the browser has had a frame to paint.
	 *
	 * These reports build thousands of rows, and building them holds the thread: shown and
	 * then rendered in the same task, the indicator is only ever painted once the work it
	 * was announcing is already over.
	 */
	function afterPaint(work) {
		if (window.requestAnimationFrame) {
			window.requestAnimationFrame(function () {
				window.setTimeout(work, 0);
			});
			return;
		}
		window.setTimeout(work, 0);
	}

	function render() {
		var error = sel('gi-error');
		sel('gi-loading').style.display = 'none';
		body.textContent = '';
		if (!payload) {
			sel('gi-empty').style.display = 'none';
			sel('gi-count').textContent = '';
			return;
		}
		if (payload.unavailable) {
			var advice = (String(payload.reason).indexOf('404') >= 0)
				? ' Expose it under management.endpoints.web.exposure.include.' : '';
			fail('The ' + payload.endpoint + ' endpoint could not be read on this instance — '
				+ payload.reason + '.' + advice);
			sel('gi-empty').style.display = 'none';
			sel('gi-count').textContent = '';
			return;
		}
		error.style.display = 'none';
		var rendered = RENDERERS[viewId](payload);
		sel('gi-empty').style.display = rendered.count ? 'none' : '';
		if (rendered.count) {
			body.appendChild(rendered.element);
		}
		var unit = (rendered.count === 1) ? rendered.unit : (rendered.plural || rendered.unit + 's');
		sel('gi-count').textContent = rendered.count + ' ' + unit
			+ (rendered.note ? ' · ' + rendered.note : '');
	}

	function query() {
		var chosen = instance();
		return chosen ? '?instance=' + encodeURIComponent(chosen) : '';
	}

	/**
	 * Reads the endpoint and draws what came back.
	 *
	 * A silent read is one the reader did not ask for &mdash; the one following a level
	 * they just set. It leaves the table standing while it goes and puts the page back
	 * where it was afterwards: emptying the view and scrolling to the top under someone
	 * who has just clicked a row is indistinguishable from having lost their place.
	 * @param {boolean} silent whether to leave the current view standing
	 */
	function load(silent) {
		var place = silent ? placeOnScreen() : null;
		if (!silent) {
			busy(true);
		}
		fetch(dataUrl + query(), { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(function (json) {
				payload = json;
				afterPaint(function () {
					render();
					restore(place);
				});
			})
			.catch(function () {
				payload = null;
				render();
				fail('The gateway could not be reached.');
			});
	}

	/** Where the reader is, across the redraw that is about to replace the rows. */
	function placeOnScreen() {
		return { inner: body.scrollTop, outer: window.pageYOffset };
	}

	/*
	 * Put back, and put back at once. Bootstrap sets `scroll-behavior: smooth` on the root,
	 * so scrolling the page back would glide rather than land — visible every time a level
	 * is set. The behaviour is suspended for the length of the correction.
	 */
	function restore(place) {
		if (!place) {
			return;
		}
		body.scrollTop = place.inner;
		if (window.pageYOffset === place.outer) {
			return;
		}
		var root = document.documentElement;
		var behaviour = root.style.scrollBehavior;
		root.style.scrollBehavior = 'auto';
		window.scrollTo(0, place.outer);
		root.style.scrollBehavior = behaviour;
	}

	/*
	 * Redrawing on every keystroke means rebuilding thousands of rows on every keystroke,
	 * which is what makes the box feel stuck. The filter waits for a pause instead.
	 */
	function debounced(work) {
		var pending = null;
		return function () {
			window.clearTimeout(pending);
			pending = window.setTimeout(work, 180);
		};
	}

	sel('gi-search').addEventListener('input', debounced(function () {
		busy(true);
		afterPaint(render);
	}));
	sel('gi-refresh').addEventListener('click', load);
	if (sel('gi-outcome')) {
		sel('gi-outcome').addEventListener('change', render);
		window.gatewayUi.remember(sel('gi-outcome'));
	}
	if (sel('gi-instance')) {
		sel('gi-instance').addEventListener('change', load);
		window.gatewayUi.remember(sel('gi-instance'));
	}
	load();
})();
