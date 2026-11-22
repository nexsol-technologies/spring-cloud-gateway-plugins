/*
 * Audit view. Polls the in-memory event tail and renders it newest first, colour-coding the
 * status. A row expands into every attribute the audit plugin collected for that exchange,
 * which is where the JWT, trace and header details live.
 */
(function () {
	var tbody = document.getElementById('ga-tbody');
	if (!tbody) {
		return;
	}

	var dataUrl = tbody.getAttribute('data-url') || '/ui/audit/events';
	var LIMIT = 100;
	var POLL_MS = 3000;
	// Rows the user expanded, kept across refreshes so a detail does not close under them.
	var expanded = {};
	var request = window.gatewayUi.sequence();

	function sel(id) {
		return document.getElementById(id);
	}

	function statusClass(code) {
		if (code >= 500) {
			return 'text-bg-danger';
		}
		if (code >= 400) {
			return 'text-bg-warning';
		}
		if (code >= 300) {
			return 'text-bg-info';
		}
		if (code >= 200) {
			return 'text-bg-success';
		}
		return 'text-bg-secondary';
	}

	function time(timestamp) {
		var parsed = new Date(timestamp);
		return isNaN(parsed.getTime()) ? String(timestamp || '') : parsed.toLocaleTimeString();
	}

	function text(value) {
		return (value === null || value === undefined || value === '') ? '—' : String(value);
	}

	function cell(row, value, className) {
		var td = document.createElement('td');
		td.textContent = text(value);
		if (className) {
			td.className = className;
		}
		row.appendChild(td);
		return td;
	}

	// Stable key for an event: timestamp + path + trace is enough to remember which rows
	// were expanded. Never the position in the list: it is newest first, so every event
	// that arrives moves all the others down one.
	function keyOf(event) {
		return [event.timestamp, event.path, event.traceId].join('|');
	}

	function detailRow(event) {
		var row = document.createElement('tr');
		row.className = 'ga-detail';
		var td = document.createElement('td');
		td.colSpan = 7;
		td.className = 'bg-body-tertiary';

		var table = document.createElement('table');
		table.className = 'table table-sm mb-0 small';
		var attributes = event.attributes || {};
		Object.keys(attributes).forEach(function (name) {
			var line = document.createElement('tr');
			var nameCell = document.createElement('th');
			nameCell.className = 'text-secondary fw-normal';
			nameCell.style.width = '18rem';
			nameCell.textContent = name;
			var valueCell = document.createElement('td');
			valueCell.className = 'text-break font-monospace';
			valueCell.textContent = text(attributes[name]);
			line.appendChild(nameCell);
			line.appendChild(valueCell);
			table.appendChild(line);
		});
		if (!Object.keys(attributes).length) {
			td.textContent = 'No attribute recorded for this exchange.';
		}
		else {
			td.appendChild(table);
		}
		row.appendChild(td);
		return row;
	}

	function render(events) {
		tbody.textContent = '';
		sel('ga-empty').style.display = events.length ? 'none' : '';
		// The events that left the tail take their key with them, or the set would grow
		// for as long as the page stays open.
		var shown = {};

		events.forEach(function (event) {
			var key = keyOf(event);
			shown[key] = true;
			var row = document.createElement('tr');
			cell(row, time(event.timestamp), 'text-nowrap');
			cell(row, event.method);
			cell(row, event.path, 'text-break');

			var statusCell = document.createElement('td');
			var badge = document.createElement('span');
			badge.className = 'badge ' + statusClass(event.statusCode);
			badge.textContent = event.statusCode ? event.statusCode + ' ' + text(event.status) : text(event.status);
			statusCell.appendChild(badge);
			row.appendChild(statusCell);

			cell(row, event.user);
			cell(row, event.ip);
			cell(row, event.traceId, 'text-break font-monospace small');

			var detail = detailRow(event);
			window.gatewayUi.expandable(row, detail, key, expanded);

			tbody.appendChild(row);
			tbody.appendChild(detail);
		});
		Object.keys(expanded).forEach(function (key) {
			if (!shown[key]) {
				delete expanded[key];
			}
		});
	}

	function load() {
		var params = new URLSearchParams({
			status: sel('ga-status').value,
			query: sel('ga-query').value,
			limit: String(LIMIT)
		});
		var latest = request();
		window.gatewayUi.getJson(dataUrl + '?' + params.toString())
			.then(function (json) {
				if (!latest()) {
					return;
				}
				window.gatewayUi.keepFocus(tbody, 'data-gw-expander', function () {
					render(json || []);
				});
				window.gatewayUi.read('ga-age');
			})
			.catch(function () {
				// The rows on screen stay: a read that failed says nothing about the
				// trail being empty.
				if (latest()) {
					window.gatewayUi.failed('ga-age');
				}
			});
	}

	var live = window.gatewayUi.poller(load, POLL_MS);

	sel('ga-status').addEventListener('change', load);
	sel('ga-query').addEventListener('input', window.gatewayUi.debounce(load, 250));
	sel('ga-refresh').addEventListener('click', load);
	sel('ga-auto').addEventListener('change', function () {
		live(sel('ga-auto').checked);
	});

	// Restored before the first load, which reads them. The search box is not remembered:
	// a query kept across page loads would hide every row and read as an empty audit
	// trail.
	['ga-status', 'ga-auto'].forEach(function (id) {
		window.gatewayUi.remember(sel(id));
	});

	load();
	live(sel('ga-auto').checked);
})();
