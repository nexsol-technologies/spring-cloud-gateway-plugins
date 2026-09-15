/*
 * Route scores view. Fetches the per-route security scores (worst first) and the findings
 * behind them, draws an overall gauge, and renders one row per route with its score as a
 * bar. A row expands into the findings that cost the route its points.
 */
(function () {
	var tbody = document.getElementById('psr-tbody');
	if (!tbody) {
		return;
	}

	var dataUrl = tbody.getAttribute('data-url') || '/ui/passive-scan/routes/data';
	var findingsUrl = tbody.getAttribute('data-findings-url') || '/ui/passive-scan/findings';
	var CIRCUMFERENCE = 2 * Math.PI * 50;
	var all = [];
	var findingsByRoute = {};
	var expanded = {};

	function sel(id) {
		return document.getElementById(id);
	}

	function gradeOf(score) {
		if (score >= 80) {
			return 'GOOD';
		}
		if (score >= 60) {
			return 'FAIR';
		}
		if (score >= 40) {
			return 'POOR';
		}
		return 'BAD';
	}

	function gradeBadge(grade) {
		switch (grade) {
			case 'GOOD':
				return 'text-bg-success';
			case 'FAIR':
				return 'text-bg-warning';
			case 'POOR':
				return 'text-bg-warning';
			default:
				return 'text-bg-danger';
		}
	}

	function gradeColor(grade) {
		switch (grade) {
			case 'GOOD':
				return 'text-success';
			case 'FAIR':
			case 'POOR':
				return 'text-warning';
			default:
				return 'text-danger';
		}
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
	}

	function renderGauge(rows) {
		var arc = sel('psr-gauge-arc');
		var scoreText = sel('psr-gauge-score');
		var gradePill = sel('psr-gauge-grade');
		if (!arc || !scoreText || !gradePill) {
			return;
		}
		if (!rows.length) {
			scoreText.textContent = '–';
			gradePill.textContent = '–';
			gradePill.className = 'badge text-bg-secondary fs-6';
			arc.setAttribute('stroke-dasharray', '0 ' + CIRCUMFERENCE);
			return;
		}
		var total = rows.reduce(function (sum, route) {
			return sum + route.score;
		}, 0);
		var overall = Math.round(total / rows.length);
		var grade = gradeOf(overall);
		scoreText.textContent = overall;
		gradePill.textContent = grade;
		gradePill.className = 'badge ' + gradeBadge(grade) + ' fs-6';
		arc.setAttribute('class', gradeColor(grade));
		arc.setAttribute('stroke-dasharray', (overall / 100) * CIRCUMFERENCE + ' ' + CIRCUMFERENCE);
		sel('psr-gauge-note').textContent = 'The average of ' + rows.length + ' scored route'
				+ (rows.length > 1 ? 's' : '') + '.';
	}

	function detailRow(route) {
		var row = document.createElement('tr');
		row.style.display = expanded[route.routeId] ? '' : 'none';
		var td = document.createElement('td');
		td.colSpan = 9;
		td.className = 'bg-body-tertiary';

		var findings = findingsByRoute[route.routeId] || [];
		if (!findings.length) {
			td.className += ' text-secondary small';
			td.textContent = 'No finding on this route — it keeps the full 100.';
			row.appendChild(td);
			return row;
		}
		var table = document.createElement('table');
		table.className = 'table table-sm mb-0 small';
		findings.forEach(function (finding) {
			var line = document.createElement('tr');

			var sevCell = document.createElement('td');
			sevCell.style.width = '7rem';
			var badge = document.createElement('span');
			badge.className = 'badge ' + severityClass(finding.severity);
			badge.textContent = text(finding.severity);
			sevCell.appendChild(badge);
			line.appendChild(sevCell);

			var titleCell = document.createElement('td');
			titleCell.textContent = text(finding.title);
			line.appendChild(titleCell);

			var metaCell = document.createElement('td');
			metaCell.className = 'text-secondary text-nowrap';
			metaCell.textContent = text(finding.categoryCode) + ' · ' + text(finding.cwe) + ' · ×' + (finding.count || 1);
			line.appendChild(metaCell);

			var pathCell = document.createElement('td');
			pathCell.className = 'text-break font-monospace';
			pathCell.textContent = text(finding.method) + ' ' + text(finding.path);
			line.appendChild(pathCell);

			table.appendChild(line);
		});
		td.appendChild(table);
		row.appendChild(td);
		return row;
	}

	function matches(route) {
		var grade = sel('psr-grade').value;
		if (grade && route.grade !== grade) {
			return false;
		}
		var needle = sel('psr-query').value.trim().toLowerCase();
		if (!needle) {
			return true;
		}
		return (route.routeId || '').toLowerCase().indexOf(needle) >= 0
				|| (route.path || '').toLowerCase().indexOf(needle) >= 0;
	}

	function render() {
		var rows = all.filter(matches);
		tbody.textContent = '';
		sel('psr-empty').style.display = rows.length ? 'none' : '';
		rows.forEach(function (route) {
			var tr = document.createElement('tr');
			tr.style.cursor = 'pointer';

			var scoreCell = document.createElement('td');
			var value = document.createElement('div');
			value.className = 'fw-bold ' + gradeColor(route.grade);
			value.textContent = route.score + ' / 100';
			var bar = document.createElement('div');
			bar.className = 'progress mt-1';
			bar.style.height = '4px';
			var fill = document.createElement('div');
			fill.className = 'progress-bar ' + gradeBadge(route.grade);
			fill.style.width = route.score + '%';
			bar.appendChild(fill);
			scoreCell.appendChild(value);
			scoreCell.appendChild(bar);
			tr.appendChild(scoreCell);

			var gradeCell = document.createElement('td');
			var badge = document.createElement('span');
			badge.className = 'badge ' + gradeBadge(route.grade);
			badge.textContent = route.grade;
			gradeCell.appendChild(badge);
			tr.appendChild(gradeCell);

			cell(tr, route.routeId, 'text-break');
			cell(tr, route.path, 'text-break');
			cell(tr, route.findings);
			cell(tr, route.critical);
			cell(tr, route.high);
			cell(tr, route.medium);
			cell(tr, route.low);

			var detail = detailRow(route);
			tr.addEventListener('click', function () {
				expanded[route.routeId] = !expanded[route.routeId];
				detail.style.display = expanded[route.routeId] ? '' : 'none';
			});

			tbody.appendChild(tr);
			tbody.appendChild(detail);
		});
		renderGauge(all);
	}

	function groupFindings(findings) {
		var grouped = {};
		findings.forEach(function (finding) {
			var key = finding.routeId || '(unrouted)';
			(grouped[key] = grouped[key] || []).push(finding);
		});
		return grouped;
	}

	function load() {
		Promise.all([
			fetch(dataUrl, { headers: { Accept: 'application/json' } }).then(function (r) {
				return r.json();
			}).catch(function () {
				return [];
			}),
			fetch(findingsUrl + '?limit=1000', { headers: { Accept: 'application/json' } }).then(function (r) {
				return r.json();
			}).catch(function () {
				return [];
			})
		]).then(function (results) {
			all = results[0] || [];
			findingsByRoute = groupFindings(results[1] || []);
			render();
		});
	}

	sel('psr-grade').addEventListener('change', render);
	sel('psr-query').addEventListener('input', render);
	sel('psr-refresh').addEventListener('click', load);

	load();
})();
