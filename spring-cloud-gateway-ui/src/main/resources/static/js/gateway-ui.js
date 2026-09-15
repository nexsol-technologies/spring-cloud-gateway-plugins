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
	document.body.addEventListener('htmx:configRequest', function (event) {
		event.detail.headers[header.content] = token.content;
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
