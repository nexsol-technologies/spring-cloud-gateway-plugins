/*
 * Routes view: filters the resolved route table client-side. The table itself is rendered
 * by Thymeleaf and swapped in place by HTMX, so the filter is re-applied after every swap.
 */
(function () {
	var input = document.getElementById('gr-filter');
	if (!input) {
		return;
	}

	function apply() {
		var needle = input.value.trim().toLowerCase();
		Array.prototype.forEach.call(document.querySelectorAll('.gr-row'), function (row) {
			var haystack = (row.getAttribute('data-search') || '').toLowerCase();
			row.style.display = (!needle || haystack.indexOf(needle) !== -1) ? '' : 'none';
		});
	}

	input.addEventListener('input', apply);
	// The refresh and reload buttons replace the table, dropping the previous filtering.
	document.body.addEventListener('htmx:afterSwap', apply);
})();
