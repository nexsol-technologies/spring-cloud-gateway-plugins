/*
 * Gateway UI shell behaviour: toggle the side menu between the expanded (icon + label)
 * and collapsed (icon only) states, and remember the choice across page loads.
 *
 * It also exposes the helper the views remember their own controls with, which is where
 * that state is written and read.
 */

/**
 * The only object the shell exposes to the scripts of the views. It is loaded before them,
 * so a view can rely on it being there.
 */
window.gatewayUi = (function () {
	var PREFIX = 'gw-pref-';

	/**
	 * Restores the state a control was last left in, and keeps it up to date afterwards.
	 * A checkbox keeps whether it was checked, any other control keeps its value, under a
	 * key derived from the id of the element.
	 *
	 * A control with no stored state keeps the default it was rendered with, so clearing
	 * the site data puts every view back to the state it ships with.
	 *
	 * @param {Element} element the control to remember, ignored when absent
	 */
	function remember(element) {
		if (!element || !element.id) {
			return;
		}
		var key = PREFIX + element.id;
		var checkbox = element.type === 'checkbox';
		var stored = read(key);
		if (stored !== null) {
			if (checkbox) {
				element.checked = stored === 'true';
			}
			else {
				var shipped = element.value;
				element.value = stored;
				// A stored option that no longer exists leaves the control empty: the
				// default it was rendered with is put back.
				if (element.value !== stored) {
					element.value = shipped;
				}
			}
		}
		element.addEventListener('change', function () {
			write(key, checkbox ? String(element.checked) : element.value);
		});
	}

	function read(key) {
		try {
			return localStorage.getItem(key);
		}
		catch (ignored) {
			// Storage refused by the browser: the control keeps its default.
			return null;
		}
	}

	function write(key, value) {
		try {
			localStorage.setItem(key, value);
		}
		catch (ignored) {
			// A storage that is full or disabled must not break the view.
		}
	}

	/**
	 * The theme the page is drawn in, for the widgets that pick their own colours.
	 *
	 * @returns {string} 'dark' or 'light'
	 */
	function theme() {
		return document.documentElement.getAttribute('data-bs-theme') === 'dark' ? 'dark' : 'light';
	}

	/*
	 * How old the figures of a polling view are. The view marks its label with data-gw-age
	 * and reports each successful read; the label then ticks on its own rather than being
	 * written on each refresh, since what it is there to reveal is a poll that stopped
	 * answering, and a label only written by a successful load would stay reassuringly at
	 * "just now". A failed poll reports nothing: the figures on screen got older, not
	 * younger.
	 */
	var readTimes = {};
	// The labels whose last read failed. The figures under such a label are the ones of the
	// last read that worked, and the label says so instead of ageing in silence.
	var failedReads = {};

	function age(id) {
		var seconds = Math.round((Date.now() - readTimes[id]) / 1000);
		if (seconds < 5) {
			return 'just now';
		}
		if (seconds < 90) {
			return seconds + 's ago';
		}
		return Math.round(seconds / 60) + 'm ago';
	}

	function tick() {
		Array.prototype.forEach.call(document.querySelectorAll('[data-gw-age]'), function (label) {
			var failed = !!failedReads[label.id];
			label.classList.toggle('text-danger', failed);
			if (failed) {
				label.textContent = readTimes[label.id] ? 'read failed · figures from ' + age(label.id)
					: 'read failed';
			}
			else if (readTimes[label.id]) {
				label.textContent = age(label.id);
			}
		});
	}

	function recordRead(id) {
		readTimes[id] = Date.now();
		failedReads[id] = false;
		tick();
	}

	function recordFailure(id) {
		failedReads[id] = true;
		tick();
	}

	setInterval(tick, 1000);

	/**
	 * Escapes a value for the places that take markup: a string built for innerHTML, and
	 * the tooltip of a chart, which ECharts assigns as markup. Route ids, caller names and
	 * URIs come from the traffic and from remote registries, never from this console.
	 *
	 * @param {*} value the value to escape
	 * @returns {string} the value as text safe to put in markup, empty when absent
	 */
	function escape(value) {
		if (value === null || value === undefined) {
			return '';
		}
		return String(value).replace(/[&<>"']/g, function (character) {
			return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
		});
	}

	var SIGN_IN_KEY = 'gw-sign-in';

	/*
	 * A 401 on a read means the session ended under an open tab. The page is loaded again,
	 * which is what the console answers with its login page; a read can only be told "no".
	 * Once per ten seconds at most: a page that loads while its read is refused would
	 * otherwise reload for ever.
	 */
	function signIn() {
		try {
			var last = parseInt(sessionStorage.getItem(SIGN_IN_KEY), 10) || 0;
			if (Date.now() - last < 10000) {
				return;
			}
			sessionStorage.setItem(SIGN_IN_KEY, String(Date.now()));
		}
		catch (ignored) {
			// Storage refused by the browser: nothing remembers the last attempt, so none
			// is made rather than risking the loop.
			return;
		}
		window.location.reload();
	}

	/**
	 * Reads a JSON endpoint of the console. Anything but a 2xx is a failure, whatever its
	 * body: an error page carrying JSON is not the figures that were asked for.
	 *
	 * @param {string} url the endpoint to read
	 * @returns {Promise<*>} the payload, or a rejection when the read failed
	 */
	function getJson(url) {
		return fetch(url, { headers: { Accept: 'application/json' } }).then(function (response) {
			if (response.status === 401) {
				signIn();
			}
			if (!response.ok) {
				throw new Error('HTTP ' + response.status);
			}
			return response.json();
		});
	}

	/**
	 * A poll that rests while nobody is looking. The returned function turns it on and
	 * off; while it is on, a hidden tab issues no request and reads once when it is shown
	 * again.
	 *
	 * @param {function} load the read to repeat
	 * @param {number} interval the period, in milliseconds
	 * @returns {function(boolean)} the switch of the poll
	 */
	function poller(load, interval) {
		var timer = null;
		document.addEventListener('visibilitychange', function () {
			if (timer && !document.hidden) {
				load();
			}
		});
		return function (enabled) {
			if (timer) {
				clearInterval(timer);
				timer = null;
			}
			if (enabled) {
				timer = setInterval(function () {
					if (!document.hidden) {
						load();
					}
				}, interval);
			}
		};
	}

	/**
	 * Tells the answer of the latest request from the ones it overtook. Each call opens a
	 * request and returns a function saying whether that request is still the latest one:
	 * an answer arriving late must not be drawn over a newer one.
	 *
	 * @returns {function(): function(): boolean} the opener of a request
	 */
	function sequence() {
		var latest = 0;
		return function () {
			var mine = ++latest;
			return function () {
				return mine === latest;
			};
		};
	}

	/**
	 * Delays a call until its trigger has been quiet, so a box being typed in issues one
	 * request rather than one per keystroke.
	 *
	 * @param {function} action the call to delay
	 * @param {number} wait the quiet period, in milliseconds
	 * @returns {function} the delayed call
	 */
	function debounce(action, wait) {
		var timer = null;
		return function () {
			clearTimeout(timer);
			timer = setTimeout(action, wait);
		};
	}

	/**
	 * Makes a table row open and close the detail row under it. The row answers a click
	 * anywhere on it; the button put in its first cell is what a keyboard reaches and what
	 * a screen reader announces the state of.
	 *
	 * @param {Element} row the row to click
	 * @param {Element} detail the row it reveals
	 * @param {string} key what names this row across the re-renders of its table
	 * @param {Object} expanded the keys of the rows currently open, updated in place
	 */
	function expandable(row, detail, key, expanded) {
		var button = document.createElement('button');
		button.type = 'button';
		button.className = 'btn btn-link btn-sm p-0 me-2 text-decoration-none gw-expander';
		button.setAttribute('aria-label', 'Details');
		button.setAttribute('data-gw-expander', key);

		function apply() {
			var open = !!expanded[key];
			detail.style.display = open ? '' : 'none';
			button.setAttribute('aria-expanded', String(open));
			button.textContent = open ? '\u25BE' : '\u25B8';
		}

		row.style.cursor = 'pointer';
		// The button needs no listener of its own: its click bubbles to the row.
		row.addEventListener('click', function () {
			if (expanded[key]) {
				delete expanded[key];
			}
			else {
				expanded[key] = true;
			}
			apply();
		});
		row.firstChild.insertBefore(button, row.firstChild.firstChild);
		apply();
	}

	/**
	 * Runs a render that replaces the content of a container, and gives the keyboard
	 * focus back to the element standing where the focused one was. A table rebuilt on
	 * every poll would otherwise drop the focus to the top of the page every few seconds.
	 *
	 * @param {Element} container what the render replaces the content of
	 * @param {string} attribute the attribute naming an element across renders
	 * @param {function} render the render to run
	 */
	function keepFocus(container, attribute, render) {
		var active = document.activeElement;
		var name = (active && container.contains(active)) ? active.getAttribute(attribute) : null;
		render();
		if (name === null) {
			return;
		}
		Array.prototype.some.call(container.querySelectorAll('[' + attribute + ']'), function (element) {
			if (element.getAttribute(attribute) === name) {
				element.focus();
				return true;
			}
			return false;
		});
	}

	/**
	 * Makes column headers sort their table, from the keyboard as well as from the mouse:
	 * the label of each becomes a button, and the header says which way it sorts.
	 *
	 * @param {NodeList} headers the headers, each naming its column in data-sort
	 * @param {function(string): number} sort sorts on a column and returns the direction
	 * it now sorts in, 1 ascending and -1 descending
	 * @returns {function(string, number)} marks the column the table starts sorted on
	 */
	function sortable(headers, sort) {
		function mark(key, direction) {
			Array.prototype.forEach.call(headers, function (header) {
				if (header.getAttribute('data-sort') === key) {
					header.setAttribute('aria-sort', direction > 0 ? 'ascending' : 'descending');
				}
				else {
					header.removeAttribute('aria-sort');
				}
			});
		}

		Array.prototype.forEach.call(headers, function (header) {
			var button = document.createElement('button');
			button.type = 'button';
			button.className = 'gw-sort-button';
			while (header.firstChild) {
				button.appendChild(header.firstChild);
			}
			header.appendChild(button);
			header.addEventListener('click', function () {
				var key = header.getAttribute('data-sort');
				mark(key, sort(key));
			});
		});
		return mark;
	}

	return {
		read: recordRead,
		failed: recordFailure,
		expandable: expandable,
		sortable: sortable,
		keepFocus: keepFocus,
		remember: remember,
		theme: theme,
		escape: escape,
		getJson: getJson,
		signIn: signIn,
		poller: poller,
		sequence: sequence,
		debounce: debounce
	};
})();

(function () {
	var STORAGE_KEY = 'gw-sidebar-collapsed';
	var root = document.documentElement;
	var toggle = document.getElementById('gw-toggle');
	if (!toggle) {
		return;
	}

	function collapsed() {
		return root.classList.contains('gw-collapsed');
	}

	function apply(folded) {
		root.classList.toggle('gw-collapsed', folded);
		toggle.setAttribute('aria-expanded', String(!folded));
	}

	// The head has already applied the stored state to the page; this is the button
	// catching up with it.
	apply(collapsed());

	toggle.addEventListener('click', function () {
		apply(!collapsed());
		localStorage.setItem(STORAGE_KEY, String(collapsed()));
	});
})();

/*
 * The progress bar of a navigation.
 *
 * Every view of this console is a page load, so between the click and the new page nothing
 * on screen moves and the click reads as ignored. The bar goes up the moment a navigation
 * starts and comes down when the next page paints — including on a page restored from the
 * back-forward cache, which paints without loading anything and would otherwise keep the
 * bar of the navigation that left it.
 */
(function () {
	var bar = document.getElementById('gw-progress');
	if (!bar) {
		return;
	}

	function start(link) {
		bar.hidden = false;
		if (link && link.classList.contains('gw-nav-link')) {
			link.classList.add('gw-nav-link-busy');
		}
	}

	function stop() {
		bar.hidden = true;
		Array.prototype.forEach.call(document.querySelectorAll('.gw-nav-link-busy'), function (link) {
			link.classList.remove('gw-nav-link-busy');
		});
	}

	document.addEventListener('click', function (event) {
		// A modified click opens elsewhere, and this page is not going anywhere.
		if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey
				|| event.shiftKey || event.altKey) {
			return;
		}
		var link = event.target.closest ? event.target.closest('a[href]') : null;
		var href = link && link.getAttribute('href');
		if (!href || href.charAt(0) === '#' || link.target === '_blank' || link.hasAttribute('download')
				|| link.href.indexOf(window.location.origin) !== 0) {
			return;
		}
		start(link);
	});

	// A form that navigates: signing out, and the login form of the console.
	document.addEventListener('submit', function (event) {
		if (!event.defaultPrevented) {
			start(null);
		}
	});

	window.addEventListener('pageshow', stop);
	window.addEventListener('pagehide', stop);
})();

/*
 * Folding menu sections. The state is remembered per section, and the shell renders a
 * section open when the page being read is one of its own — so the fold is only ever
 * restored over a section the reader is not currently inside.
 */
(function () {
	var PREFIX = 'gw-nav-group-';
	Array.prototype.forEach.call(document.querySelectorAll('.gw-nav-group'), function (group) {
		var id = group.getAttribute('data-gw-group');
		var toggle = group.querySelector('.gw-nav-group-toggle');
		if (!id || !toggle) {
			return;
		}
		// A section holding the active view stays open whatever was stored: folding the
		// page under the reader is never what they asked for.
		if (toggle.getAttribute('aria-expanded') !== 'true') {
			var stored = null;
			try {
				stored = localStorage.getItem(PREFIX + id);
			}
			catch (ignored) {
				// Storage refused by the browser: the section keeps the state it rendered in.
			}
			toggle.setAttribute('aria-expanded', String(stored !== 'false'));
		}
		toggle.addEventListener('click', function () {
			var open = toggle.getAttribute('aria-expanded') !== 'true';
			toggle.setAttribute('aria-expanded', String(open));
			try {
				localStorage.setItem(PREFIX + id, String(open));
			}
			catch (ignored) {
				// A storage that is full or disabled must not break the fold.
			}
		});
	});
})();

/*
 * Where the menu was scrolled to, across the page load a click on it starts.
 *
 * Every view of this console is a full page load, so the menu is rebuilt with each one and
 * comes back at the top. A menu long enough to scroll then takes the reader away from the
 * entry they just clicked — the further down it sits, the further the menu jumps. The offset
 * is put back before the page paints: this script is the last thing in the body, ahead of
 * the first frame, and after the sections above have settled — a group that folds or unfolds
 * under a restored offset moves the menu again, or has the browser clamp it.
 *
 * sessionStorage rather than localStorage: this is where one tab was, not a preference to
 * carry into the next one.
 */
(function () {
	var KEY = 'gw-nav-scroll';
	var nav = document.querySelector('.gw-nav');
	if (!nav) {
		return;
	}

	try {
		nav.scrollTop = parseInt(sessionStorage.getItem(KEY), 10) || 0;
	}
	catch (ignored) {
		// Storage refused by the browser: the menu starts at the top, as it did before.
	}

	// pagehide rather than a listener on every scroll: it covers a click, a reload and a
	// back alike, and writes once instead of once a frame.
	window.addEventListener('pagehide', function () {
		try {
			sessionStorage.setItem(KEY, String(nav.scrollTop));
		}
		catch (ignored) {
			// Same again: nothing is remembered, nothing else breaks.
		}
	});
})();

/*
 * CSRF token on every HTMX request. A console that authenticates keeps its writes behind a
 * session cookie, which a form can carry a hidden field for but an HTMX request cannot:
 * the token is put on the request as a header instead, from the meta tags the shell renders
 * it in. Nothing is added when the console is open, since there is then no token to add.
 */
(function () {
	var token = document.querySelector('meta[name="_csrf"]');
	var header = document.querySelector('meta[name="_csrf_header"]');
	if (!token || !header) {
		return;
	}
	document.addEventListener('htmx:config:request', function (event) {
		event.detail.ctx.request.headers[header.content] = token.content;
	});
})();

/*
 * Signing out takes with it what the OpenAPI view kept for its request client. Scalar stores
 * the token and the scopes entered there in the local storage of this origin, which outlives
 * the session: left in place, the next person signing in on this browser inherits them.
 */
(function () {
	Array.prototype.forEach.call(document.querySelectorAll('form[action$="/ui/logout"]'), function (form) {
		form.addEventListener('submit', function () {
			try {
				Object.keys(localStorage).forEach(function (key) {
					if (key.indexOf('scalar-reference-auth') === 0) {
						localStorage.removeItem(key);
					}
				});
			}
			catch (ignored) {
				// Storage refused by the browser: nothing was kept there either.
			}
		});
	});
})();

/*
 * A failed HTMX request says so. Error answers are kept out of the swap, so without this a
 * button whose request failed leaves the page exactly as a button that did nothing: reloading
 * a route table that did not change looks the same whether it worked or answered 500.
 */
(function () {
	function report(message) {
		var container = document.getElementById('toast-container');
		if (!container) {
			container = document.createElement('div');
			container.id = 'toast-container';
			container.className = 'toast-container position-fixed bottom-0 end-0 p-3';
			document.body.appendChild(container);
		}
		var toast = document.createElement('div');
		toast.className = 'toast align-items-center border-0 text-bg-danger';
		toast.setAttribute('role', 'alert');
		var row = document.createElement('div');
		row.className = 'd-flex';
		var body = document.createElement('div');
		body.className = 'toast-body';
		body.textContent = message;
		var close = document.createElement('button');
		close.type = 'button';
		close.className = 'btn-close btn-close-white me-2 m-auto';
		close.setAttribute('data-bs-dismiss', 'toast');
		close.setAttribute('aria-label', 'Close');
		row.appendChild(body);
		row.appendChild(close);
		toast.appendChild(row);
		container.appendChild(toast);
		toast.addEventListener('hidden.bs.toast', function () {
			toast.remove();
		});
		new bootstrap.Toast(toast, { delay: 6000 }).show();
	}

	document.addEventListener('htmx:response:error', function (event) {
		var response = event.detail && event.detail.ctx && event.detail.ctx.response;
		// A 401 or a 403 carries the page to go to, and an answer naming its own toast
		// has said what it had to say.
		if (!response || response.headers.get('HX-Redirect') || response.headers.get('HX-Trigger')) {
			return;
		}
		report('The request failed: the gateway answered ' + response.status + '.');
	});

	document.addEventListener('htmx:error', function () {
		report('The request failed before the gateway answered.');
	});
})();

/*
 * Dialogs are moved to the body before anything opens one.
 *
 * A page of this console arrives with a fade, and an element being animated is a stacking
 * context of its own: a dialog rendered inside the page is trapped in it, while the backdrop
 * Bootstrap adds is a child of the body. The backdrop then covers the dialog however high its
 * own z-index is — the dialog is drawn, and every click on it lands on the backdrop instead.
 * Reparenting is the fix that survives the next dialog someone adds to a view.
 */
(function () {
	var dialogs = document.querySelectorAll('.gw-content .modal');
	Array.prototype.forEach.call(dialogs, function (dialog) {
		document.body.appendChild(dialog);
	});
})();

/*
 * The expand button of a picture. A button carrying data-gw-expand="<card id>" gives that
 * card the whole content column and gives itself back the way out, so a view adds a full
 * screen by writing one button rather than a behaviour of its own.
 *
 * The card is fixed over the content rather than promoted to the fullscreen API: the menu is
 * meant to stay beside it, and a fullscreen element has nothing beside it.
 */
(function () {
	var buttons = document.querySelectorAll('[data-gw-expand]');
	if (!buttons.length) {
		return;
	}

	function card(button) {
		return document.getElementById(button.getAttribute('data-gw-expand'));
	}

	function apply(button, on) {
		card(button).classList.toggle('gw-expanded', on);
		document.body.classList.toggle('gw-expanded-open', on);
		button.setAttribute('aria-pressed', String(on));
		button.classList.toggle('btn-outline-secondary', !on);
		button.classList.toggle('btn-secondary', on);
		button.textContent = on ? 'Exit full screen' : 'Expand';
		button.title = on ? 'Back to the page (Escape)'
			: 'Give the picture the whole page (Escape to come back)';
	}

	Array.prototype.forEach.call(buttons, function (button) {
		button.addEventListener('click', function () {
			apply(button, !card(button).classList.contains('gw-expanded'));
		});
		document.addEventListener('keydown', function (event) {
			if (event.key === 'Escape' && card(button).classList.contains('gw-expanded')) {
				apply(button, false);
			}
		});
	});
})();

/*
 * Light / dark switch. The theme itself is applied by the head of the shell, before the
 * page paints; this only writes the choice down and puts it in place straight away.
 */
(function () {
	var STORAGE_KEY = 'gw-theme';
	var button = document.getElementById('gw-theme');
	if (!button) {
		return;
	}

	button.addEventListener('click', function () {
		var next = window.gatewayUi.theme() === 'dark' ? 'light' : 'dark';
		document.documentElement.setAttribute('data-bs-theme', next);
		try {
			localStorage.setItem(STORAGE_KEY, next);
		}
		catch (ignored) {
			// A storage that is full or disabled must not break the switch: the theme
			// still changes, it is simply not remembered.
		}
		// A widget that picks its colours when it is created cannot be restyled in place.
		// The page hosting one comes back in the new theme rather than staying half-lit.
		if (document.querySelector('[data-gw-theme-reload]')) {
			location.reload();
		}
	});
})();

/*
 * The Live badge of a view that polls. A view says where its badge goes and which switch it
 * reports on, through data-gw-live; watching that switch is the shell's business, so no view
 * carries a line of script for it.
 *
 * It is bound once the page has been read rather than on the spot: a remembered switch is
 * restored by the view that owns it, after this file has run and without firing an event, so
 * a badge bound any earlier would report the state the page shipped with rather than the one
 * the reader left it in.
 */
(function () {
	'use strict';

	function bind() {
		Array.prototype.forEach.call(document.querySelectorAll('[data-gw-live]'), function (badge) {
			var auto = document.getElementById(badge.getAttribute('data-gw-live'));
			if (!auto) {
				return;
			}
			var follow = function () {
				badge.hidden = !auto.checked;
			};
			auto.addEventListener('change', follow);
			follow();
		});
	}

	if (document.readyState === 'loading') {
		document.addEventListener('DOMContentLoaded', bind);
	}
	else {
		bind();
	}
})();
