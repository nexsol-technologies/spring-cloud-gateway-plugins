/*
 * Gateway UI shell behaviour: toggle the side menu between the expanded (icon + label)
 * and collapsed (icon only) states, and remember the choice across page loads.
 *
 * It also exposes the helper the views remember their own controls with, so where that
 * state lives is decided once, here.
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
				// A stored option that no longer exists would leave the control empty:
				// fall back on the default rather than on nothing.
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

	return { remember: remember };
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
