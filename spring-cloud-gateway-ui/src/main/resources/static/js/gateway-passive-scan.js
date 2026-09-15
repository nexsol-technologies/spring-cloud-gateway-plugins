/*
 * Passive scan view. Polls the aggregated findings and the running summary, rendering one
 * row per distinct problem with its occurrence count and colour-coded severity. A row
 * expands into the description, the remediation and the evidence the scanner recorded. No
 * request is issued by the scan itself; this only reads what the analyser already observed.
 */
(function () {
	var tbody = document.getElementById('ps-tbody');
	if (!tbody) {
		return;
	}

	var dataUrl = tbody.getAttribute('data-url') || '/ui/passive-scan/findings';
	var summaryUrl = tbody.getAttribute('data-summary-url') || '/ui/passive-scan/summary';
	var coverageEl = document.getElementById('ps-coverage');
	var coverageUrl = coverageEl ? (coverageEl.getAttribute('data-url') || '/ui/passive-scan/coverage') : null;
	var LIMIT = 200;
	var POLL_MS = 3000;
	var SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'];
	var COLSPAN = 9;
	var pollTimer = null;
	var expanded = {};

	function sel(id) {
		return document.getElementById(id);
	}

	function severityClass(severity) {
		switch (severity) {
			case 'CRITICAL':
			case 'HIGH':
				return 'text-bg-danger';
			case 'MEDIUM':
				return 'text-bg-warning';
			case 'LOW':
				return 'text-bg-info';
			default:
				return 'text-bg-secondary';
		}
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

	function keyOf(finding, index) {
		return [finding.scanner, finding.method, finding.path, finding.title, index].join('|');
	}

	function section(parent, label, value) {
		if (!value) {
			return;
		}
		var wrap = document.createElement('div');
		wrap.className = 'mb-2';
		var head = document.createElement('div');
		head.className = 'text-secondary small text-uppercase fw-semibold';
		head.textContent = label;
		var body = document.createElement('div');
		body.className = 'small';
		body.textContent = value;
		wrap.appendChild(head);
		wrap.appendChild(body);
		parent.appendChild(wrap);
	}

	function detailRow(finding, key) {
		var row = document.createElement('tr');
		row.style.display = expanded[key] ? '' : 'none';
		var td = document.createElement('td');
		td.colSpan = COLSPAN;
		td.className = 'bg-body-tertiary';

		section(td, 'Rule', finding.ruleId + ' · ' + finding.categoryCode + ' · ' + text(finding.cwe) + ' · '
				+ text(finding.confidence).toLowerCase() + ' confidence');
		section(td, 'Description', finding.detail);
		section(td, 'Remediation', finding.remediation);
		if (finding.reference) {
			var refWrap = document.createElement('div');
			refWrap.className = 'mb-2';
			var link = document.createElement('a');
			link.href = finding.reference;
			link.target = '_blank';
			link.rel = 'noopener';
			link.className = 'small';
			link.textContent = 'Learn more';
			refWrap.appendChild(link);
			td.appendChild(refWrap);
		}

		var evidence = finding.evidence || {};
		var keys = Object.keys(evidence);
		if (keys.length) {
			var head = document.createElement('div');
			head.className = 'text-secondary small text-uppercase fw-semibold';
			head.textContent = 'Evidence';
			td.appendChild(head);
			var table = document.createElement('table');
			table.className = 'table table-sm mb-0 small';
			keys.forEach(function (name) {
				var line = document.createElement('tr');
				var nameCell = document.createElement('th');
				nameCell.className = 'text-secondary fw-normal';
				nameCell.style.width = '18rem';
				nameCell.textContent = name;
				var valueCell = document.createElement('td');
				valueCell.className = 'text-break font-monospace';
				valueCell.textContent = text(evidence[name]);
				line.appendChild(nameCell);
				line.appendChild(valueCell);
				table.appendChild(line);
			});
			td.appendChild(table);
		}
		row.appendChild(td);
		return row;
	}

	function render(findings) {
		tbody.textContent = '';
		sel('ps-empty').style.display = findings.length ? 'none' : '';
		findings.forEach(function (finding, index) {
			var key = keyOf(finding, index);
			var row = document.createElement('tr');
			row.style.cursor = 'pointer';
			cell(row, time(finding.lastSeen), 'text-nowrap');

			var countCell = document.createElement('td');
			var countBadge = document.createElement('span');
			countBadge.className = 'badge text-bg-secondary';
			countBadge.textContent = '×' + (finding.count || 1);
			countCell.appendChild(countBadge);
			row.appendChild(countCell);

			var sevCell = document.createElement('td');
			var badge = document.createElement('span');
			badge.className = 'badge ' + severityClass(finding.severity);
			badge.textContent = text(finding.severity);
			sevCell.appendChild(badge);
			// The confidence is not on the row; it is on the Rule line of the expanded panel.
			row.appendChild(sevCell);

			cell(row, finding.categoryCode, 'text-nowrap');
			cell(row, finding.cwe, 'text-nowrap');
			cell(row, finding.scanner, 'text-nowrap');
			cell(row, finding.method);
			cell(row, finding.path, 'text-break');
			cell(row, finding.title, 'text-break');

			var detail = detailRow(finding, key);
			row.addEventListener('click', function () {
				expanded[key] = !expanded[key];
				detail.style.display = expanded[key] ? '' : 'none';
			});
			tbody.appendChild(row);
			tbody.appendChild(detail);
		});
	}

	function renderSummary(summary) {
		var bySeverity = (summary && summary.bySeverity) || {};
		sel('ps-total').textContent = (summary && summary.total) || 0;
		SEVERITIES.forEach(function (severity) {
			var node = sel('ps-count-' + severity);
			if (node) {
				node.textContent = bySeverity[severity] || 0;
			}
		});
	}

	function coverageClass(status) {
		switch (status) {
			case 'GOOD':
				return 'text-bg-success';
			case 'PARTIAL':
				return 'text-bg-warning';
			default:
				return 'text-bg-secondary';
		}
	}

	function coverageLabel(status) {
		switch (status) {
			case 'GOOD':
				return 'Covered';
			case 'PARTIAL':
				return 'Partial';
			default:
				return 'Not passive';
		}
	}

	function renderCoverage(rows) {
		if (!coverageEl) {
			return;
		}
		coverageEl.textContent = '';
		rows.forEach(function (row) {
			var tr = document.createElement('tr');
			var status = document.createElement('td');
			status.style.width = '9rem';
			var badge = document.createElement('span');
			badge.className = 'badge ' + coverageClass(row.status);
			badge.textContent = coverageLabel(row.status);
			status.appendChild(badge);
			tr.appendChild(status);
			var name = document.createElement('td');
			name.className = 'text-nowrap';
			name.innerHTML = '<span class="text-secondary small">' + text(row.code) + '</span> ' + text(row.title);
			tr.appendChild(name);
			cell(tr, row.note, 'small text-secondary');
			coverageEl.appendChild(tr);
		});
	}

	function loadCoverage() {
		if (!coverageUrl) {
			return;
		}
		fetch(coverageUrl, { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(function (json) {
				renderCoverage(json || []);
			})
			.catch(function () {
				renderCoverage([]);
			});
	}

	function load() {
		var params = new URLSearchParams({
			severity: sel('ps-severity').value,
			query: sel('ps-query').value,
			limit: String(LIMIT)
		});
		fetch(dataUrl + '?' + params.toString(), { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(function (json) {
				render(json || []);
			})
			.catch(function () {
				render([]);
			});
		fetch(summaryUrl, { headers: { Accept: 'application/json' } })
			.then(function (response) {
				return response.json();
			})
			.then(renderSummary)
			.catch(function () {
				renderSummary(null);
			});
	}

	function live(enabled) {
		if (pollTimer) {
			clearInterval(pollTimer);
			pollTimer = null;
		}
		if (enabled) {
			pollTimer = setInterval(load, POLL_MS);
		}
	}

	sel('ps-severity').addEventListener('change', load);
	sel('ps-query').addEventListener('input', load);
	sel('ps-refresh').addEventListener('click', load);
	sel('ps-live').addEventListener('change', function () {
		live(sel('ps-live').checked);
	});

	['ps-severity', 'ps-live'].forEach(function (id) {
		window.gatewayUi.remember(sel(id));
	});

	loadCoverage();
	load();
	live(sel('ps-live').checked);
})();
