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

	return { remember: remember, theme: theme };
})();

(function () {
	var STORAGE_KEY = 'gw-sidebar-collapsed';
	var shell = document.getElementById('gw-shell');
	var toggle = document.getElementById('gw-toggle');
	if (!shell || !toggle) {
		return;
	}

	function apply(collapsed) {
		shell.classList.toggle('gw-collapsed', collapsed);
		toggle.setAttribute('aria-expanded', String(!collapsed));
	}

	// Restore the previously chosen state.
	apply(localStorage.getItem(STORAGE_KEY) === 'true');

	toggle.addEventListener('click', function () {
		var collapsed = !shell.classList.contains('gw-collapsed');
		apply(collapsed);
		localStorage.setItem(STORAGE_KEY, String(collapsed));
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
	document.body.addEventListener('htmx:configRequest', function (event) {
		event.detail.headers[header.content] = token.content;
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
