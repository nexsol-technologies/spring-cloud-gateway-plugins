/*
 * Gateway UI shell behaviour: toggle the side menu between the expanded (icon + label)
 * and collapsed (icon only) states, and remember the choice across page loads.
 */
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
