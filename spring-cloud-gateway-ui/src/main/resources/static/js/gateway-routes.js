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

/*
 * Export dialog: one checkbox per source the table holds, and a download of the routes of
 * the sources left ticked.
 *
 * The list is built when the dialog opens, from the table as it stands: the two actions
 * above re-render the table on their own, and a source read from a list built once with
 * the page could be one the gateway no longer resolves.
 */
(function () {
	var dialog = document.getElementById('gr-export');
	var download = document.getElementById('gr-export-download');
	if (!dialog || !download) {
		return;
	}

	var list = document.getElementById('gr-export-sources');

	/*
	 * The one source ticked when the dialog opens: the routes a registry hands the gateway
	 * are the ones nobody has written down, so exporting them is what this is opened for.
	 * Every other source is already a file somewhere. The name is the one the table shows,
	 * derived from the locator class by RouteInventoryService.sourceName — should that name
	 * change, nothing is ticked to begin with and nothing else breaks.
	 */
	var DEFAULT_SOURCE = 'Discovery Client';

	/** The sources the table shows, in the order it shows them. */
	function sources() {
		var seen = [];
		Array.prototype.forEach.call(document.querySelectorAll('.gr-row'), function (row) {
			var source = row.getAttribute('data-source');
			if (source && seen.indexOf(source) < 0) {
				seen.push(source);
			}
		});
		return seen;
	}

	function checkbox(source, index) {
		var id = 'gr-export-source-' + index;
		var wrapper = document.createElement('div');
		wrapper.className = 'form-check';
		var input = document.createElement('input');
		input.className = 'form-check-input';
		input.type = 'checkbox';
		input.id = id;
		input.checked = (source === DEFAULT_SOURCE);
		input.value = source;
		var label = document.createElement('label');
		label.className = 'form-check-label';
		label.htmlFor = id;
		label.textContent = source;
		wrapper.appendChild(input);
		wrapper.appendChild(label);
		input.addEventListener('change', settle);
		return wrapper;
	}

	function chosen() {
		return Array.prototype.filter
			.call(list.querySelectorAll('input[type="checkbox"]'), function (input) {
				return input.checked;
			})
			.map(function (input) {
				return input.value;
			});
	}

	/*
	 * Nothing ticked is not the same request as everything ticked: the endpoint reads an
	 * empty selection as the whole configuration, so the download is refused here rather
	 * than handing back a file nobody asked for.
	 */
	function settle() {
		download.disabled = !chosen().length;
	}

	/*
	 * The endpoint, resolved against the page and checked to be on its own origin.
	 *
	 * The server renders it into the page, but it reaches this script as DOM text all the
	 * same, and DOM text handed to a navigation is how a 'javascript:' URL gets run. The
	 * check is on the resolved URL rather than on the text: a scheme of its own resolves to
	 * another origin, and a relative path resolves to this one.
	 */
	function endpoint() {
		var url = new URL(download.getAttribute('data-url'), window.location.href);
		return (url.origin === window.location.origin) ? url : null;
	}

	dialog.addEventListener('show.bs.modal', function () {
		var found = sources();
		list.textContent = '';
		if (!found.length) {
			var empty = document.createElement('span');
			empty.className = 'text-secondary small';
			empty.textContent = 'No source to export.';
			list.appendChild(empty);
		}
		found.forEach(function (source, index) {
			list.appendChild(checkbox(source, index));
		});
		settle();
	});

	download.addEventListener('click', function () {
		var query = chosen().map(function (source) {
			return 'source=' + encodeURIComponent(source);
		});
		var url = query.length ? endpoint() : null;
		if (!url) {
			return;
		}
		url.search = query.join('&');
		// A navigation rather than a fetch: the response carries Content-Disposition, so
		// the browser saves it and leaves the page where it is.
		window.location.assign(url.href);
		var open = window.bootstrap && window.bootstrap.Modal.getInstance(dialog);
		if (open) {
			open.hide();
		}
	});
})();
