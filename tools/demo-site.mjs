/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Assembles the console published to GitHub Pages: the views that read JSON, inside the
 * console's own shell, over a gateway simulated in the page.
 *
 * Nothing here is a copy of the console. The stylesheets and the scripts are the module's
 * own files, taken as they are; the shell is its layout template and each view its own
 * template, with what only a server can fill supplied here and the th: attributes dropped
 * — so a view changed in the module is a demo changed with it. What the demo supplies is
 * the one thing a published page cannot have: a gateway answering. tools/demo/gateway.js
 * stubs `fetch` over invented traffic that advances on its own.
 *
 * The script fails rather than guessing when a template no longer carries what it reads.
 * It needs nothing installed, and no build: Node reads files and writes files.
 * See tools/README.md.
 */

import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const UI = 'spring-cloud-gateway-ui/src/main';
const STATIC = `${UI}/resources/static`;
const TEMPLATES = `${UI}/resources/templates/dashboard`;
const INSIGHTS = `${UI}/java/ch/nexsol/gateway/ui/insights/InsightsController.java`;

const options = {
	out: 'target/demo-site',
	version: 'SNAPSHOT'
};

for (const argument of process.argv.slice(2)) {
	const [key, value] = argument.replace(/^--/, '').split('=');
	if (!(key in options)) {
		console.error(`Unknown option --${key}. Known: ${Object.keys(options).join(', ')}`);
		process.exit(2);
	}
	options[key] = value ?? 'true';
}

const read = (path) => readFileSync(path, 'utf8');

/* Markup surgery --------------------------------------------------------------------- */

/*
 * The element whose opening tag contains `marker`, as offsets into `markup`: from its `<`
 * to the end of its matching closing tag.
 */
function element(markup, marker, from = 0) {
	const at = markup.indexOf(marker, from);
	if (at < 0) {
		throw new Error(`No element carries ${marker}.`);
	}
	const start = markup.lastIndexOf('<', at);
	const name = markup.slice(start + 1).match(/^[\w:-]+/)[0];
	const tags = new RegExp(`<(/?)${name}\\b[^>]*>`, 'g');
	tags.lastIndex = start;
	let depth = 0;
	for (let tag = tags.exec(markup); tag; tag = tags.exec(markup)) {
		depth += tag[1] ? -1 : 1;
		if (depth === 0) {
			return { start, end: tag.index + tag[0].length };
		}
	}
	throw new Error(`The element carrying ${marker} is never closed.`);
}

function outer(markup, marker) {
	const { start, end } = element(markup, marker);
	return markup.slice(start, end);
}

function inner(markup, marker) {
	const text = outer(markup, marker);
	return text.slice(text.indexOf('>') + 1, text.lastIndexOf('<'));
}

function replaceElement(markup, marker, replacement) {
	const { start, end } = element(markup, marker);
	return markup.slice(0, start) + replacement + markup.slice(end);
}

/* A replacement that has to happen: a template that changed under it fails the run. */
function once(markup, from, to) {
	if (!markup.includes(from)) {
		throw new Error(`Expected to find ${from}.`);
	}
	return markup.replace(from, to);
}

/*
 * What is left of a template once the server is gone: no th: attribute, no th:block tag,
 * and the console's absolute paths made relative, since the site is served under the
 * repository's name.
 */
function serverless(markup) {
	return markup
		.replace(/\s+(th:[\w-]+|xmlns:th)="[^"]*"/g, '')
		.replace(/<\/?th:block[^>]*>/g, '')
		.replace(/(src|href)="\/(css|js|img)\//g, '$1="$2/');
}

/* The views -------------------------------------------------------------------------- */

/*
 * The six introspection views share one template and are told apart by the model: their
 * names, icons and sentences are read from the controller that declares them.
 */
function insightsViews() {
	const declared = [...read(INSIGHTS).matchAll(
			/views\.put\("([\w-]+)", new View\("([^"]+)", "[^"]+",\s*"([^"]+)", \d+,\s*"([\w-]+)"\)\)/g)];
	if (declared.length !== 6) {
		throw new Error(`${INSIGHTS} no longer declares its six views the way this script reads them.`);
	}
	return declared.map(([, id, label, description, icon]) => ({
		page: `${id}.html`, template: 'insights.html', slot: 'insights', label, icon, group: 'Configuration',
		band: [icon, label, description, ''], reads: `GET /ui/insights/${id}/data`,
		edit: (markup) => insightsModel(markup, id)
	}));
}

/* What the controller puts in the model of an introspection view, for a fleet of two. */
function insightsModel(markup, id) {
	let html = markup.replace(/th:attr="data-view=\$\{viewId\}[^"]*"/, `data-view="${id}" data-writable="true"
			data-url="/ui/insights/${id}/data" data-write-url="/ui/insights/loggers"`);
	if (html === markup) {
		throw new Error('insights.html no longer hands the view its URLs through th:attr on #gi-body.');
	}
	html = replaceElement(html, 'th:each="instance : ${instances}"',
			'<option value="gateway-1">gateway-1 (this one)</option>\n\t\t\t\t\t\t\t<option value="gateway-2">gateway-2</option>');
	html = id === 'loggers'
		? once(html, '>All instances<', '>All 2 instances<')
		: replaceElement(html, 'th:if="${viewId == \'loggers\'}"', '');
	return id === 'conditions' ? html : replaceElement(html, 'th:if="${viewId == \'conditions\'}"', '');
}

/* The severity row of the passive scan, which the template lays out with a th:each. */
function severityCards(markup) {
	const marker = 'th:each="sev : ${ {';
	const card = outer(markup, marker);
	const severities = card.match(/\{ \{([^}]*)\} \}/)[1].match(/[A-Z]+/g);
	const cards = severities.map((severity) => once(once(card,
			'th:text="${#strings.capitalize(#strings.toLowerCase(sev))}">Severity<',
			`>${severity.charAt(0) + severity.slice(1).toLowerCase()}<`),
			'th:id="\'ps-count-\' + ${sev}"', `id="ps-count-${severity}"`));
	return replaceElement(markup, marker, cards.join('\n'));
}

/*
 * Every page of the site, in the order of the console's side menu: its entry there, the
 * template its view is read from, the id prefix of the template's content and scripts
 * slots, and the endpoint a gateway would be read through, which the page names.
 */
const VIEWS = [
	{ page: 'flow.html', template: 'service-flow.html', slot: 'flow', label: 'Flow', icon: 'icon-flow',
		group: 'Activity', reads: 'GET /ui/service-graph/data' },
	{ page: 'traffic.html', template: 'metrics.html', slot: 'metrics', label: 'Traffic', icon: 'icon-chart',
		group: 'Activity', reads: 'GET /ui/metrics/data' },
	{ page: 'service-graph.html', template: 'service-graph.html', slot: 'graph', label: 'Service graph',
		icon: 'icon-graph', group: 'Activity', reads: 'GET /ui/service-graph/data' },
	{ page: 'audit.html', template: 'audit.html', slot: 'audit', label: 'Audit', icon: 'icon-list',
		group: 'Activity', reads: 'GET /ui/audit/events' },
	{ page: 'runtime.html', template: 'instances.html', slot: 'instances', label: 'Metrics',
		icon: 'icon-server', group: 'Runtime', reads: 'GET /ui/metrics/instances/data' },
	{ page: 'openapi.html', template: 'openapi.html', slot: 'openapi', label: 'Hub', icon: 'icon-book',
		group: 'OpenAPI', reads: 'GET /v3/api-docs/swagger-config, then each contract it lists' },
	{ page: 'passive-scan.html', template: 'passive-scan.html', slot: 'ps', label: 'Passive scan',
		icon: 'icon-shield', group: 'Security', reads: 'GET /ui/passive-scan/findings and /summary',
		edit: severityCards },
	{ page: 'route-scores.html', template: 'passive-scan-routes.html', slot: 'psr', label: 'Route scores',
		icon: 'icon-award', group: 'Security', reads: 'GET /ui/passive-scan/routes/data' },
	...insightsViews()
];

const HOME = { page: 'index.html', label: 'Home', icon: 'icon-home' };

/* The band a view opens with, rendered from its fragment with the arguments it is given. */
function band([icon, title, subtitle, coverage]) {
	let markup = outer(read(`${TEMPLATES}/fragments/view-band.html`), 'th:fragment="band(');
	markup = once(markup, '<use th:attr="href=\'#\' + ${icon}" href="#icon-plugin">', `<use href="#${icon}">`);
	markup = once(markup, 'th:utext="${title}">View<', `>${title}<`);
	markup = subtitle
		? once(markup, 'th:utext="${subtitle}">What it shows.<', `>${subtitle}<`)
		: replaceElement(markup, 'th:utext="${subtitle}"', '');
	return coverage
		? once(markup, 'th:id="${coverage}">', `id="${coverage}">`)
		: replaceElement(markup, 'th:id="${coverage}"', '');
}

/* The arguments of the band call a template opens its view with. */
function bandArguments(call) {
	return [...call.matchAll(/'((?:[^'\\]|\\.)*)'/g)].map((argument) => argument[1].replace(/\\'/g, '\''));
}

function view(spec) {
	const template = read(`${TEMPLATES}/${spec.template}`);
	let content = inner(template, `<div id="${spec.slot}-content">`);
	const call = content.match(/<div th:replace="~\{dashboard\/fragments\/view-band :: band\(([\s\S]*?)\)\}"><\/div>/);
	if (!call) {
		throw new Error(`${spec.template} no longer opens with a view-band fragment.`);
	}
	content = content.replace(call[0], band(spec.band ?? bandArguments(call[1])));
	if (spec.edit) {
		content = spec.edit(content);
	}
	return { content, scripts: inner(template, `<div id="${spec.slot}-scripts">`) };
}

/* The shell -------------------------------------------------------------------------- */

function link(item, active) {
	return `<a class="gw-nav-link${item === active ? ' active' : ''}" href="${item.page}" title="${item.label}">
						<svg class="gw-icon"><use href="#${item.icon}"></use></svg>
						<span class="gw-nav-label">${item.label}</span>
					</a>`;
}

/* The side menu: the home entry alone, then one group per run of views. */
function menu(active) {
	const groups = [...new Set(VIEWS.map((item) => item.group))];
	return [link(HOME, active), ...groups.map((group) => {
		const items = VIEWS.filter((item) => item.group === group);
		return `<div class="gw-nav-group" data-gw-group="${group.toLowerCase().replace(/ /g, '-')}">
					<button type="button" class="gw-nav-group-toggle" title="${group}" aria-expanded="${items.includes(active)}">
						<span class="gw-nav-label">${group}</span>
						<span class="gw-nav-group-count gw-nav-label">${items.length}</span>
						<svg class="gw-icon gw-nav-group-chevron"><use href="#icon-chevron"></use></svg>
					</button>
					<div class="gw-nav-group-items">
					${items.map((item) => link(item, active)).join('\n\t\t\t\t\t')}
					</div>
				</div>`;
	})].join('\n\t\t\t\t');
}

/* Said on every page, in the open: a reader who took these figures for a gateway's would be right to be cross. */
function notice(item) {
	return `<div class="alert alert-warning py-2 px-3 small mb-3" role="status">
			<strong>Demo data — nothing is being polled.</strong> This page runs the console's own
			markup, stylesheet and scripts, unchanged; the gateway it reads is simulated in the page
			and its traffic is invented.${item.reads ? ` In a gateway, this view reads <code>${item.reads}</code>.` : ''}
		</div>`;
}

function page(item, content, scripts) {
	let html = read(`${TEMPLATES}/fragments/layout.html`);
	html = html.replace(/<title[^>]*>[^<]*<\/title>/, `<title>${item.label} — Spring Cloud Gateway plugins demo</title>`);
	html = replaceElement(html, '<nav class="gw-nav">', `<nav class="gw-nav">\n\t\t\t\t${menu(item)}\n\t\t\t</nav>`);
	html = replaceElement(html, '<div class="gw-user"', '');
	html = replaceElement(html, '<form th:if="${currentUser != null}"', '');
	html = once(html, 'th:text="${uiVersion}">1.0.0<', `>${options.version}<`);
	html = once(html, '<div th:replace="${content}">Page content</div>', `${notice(item)}\n\t\t\t${content}`);
	html = once(html, '<th:block th:replace="${scripts}"></th:block>',
			`<script src="js/demo-gateway.js"></script>\n\t${scripts}`);
	return serverless(html);
}

/* The home page: what the demo is, and a tile per view, as the console's home lists them. */
function home() {
	const tiles = VIEWS.map((item) => `<div class="col-12 col-md-6 col-xl-4">
						<a class="gw-tile h-100" href="${item.page}">
							<svg class="gw-icon"><use href="#${item.icon}"></use></svg>
							<span>${item.label}</span>
						</a>
					</div>`).join('\n\t\t\t\t\t');
	return once(read('tools/demo/home.html'), '<!-- tiles -->', tiles);
}

/* The site --------------------------------------------------------------------------- */

function write(path, content) {
	const target = join(options.out, path);
	mkdirSync(dirname(target), { recursive: true });
	writeFileSync(target, content);
	console.log(target);
}

rmSync(options.out, { recursive: true, force: true });
cpSync(STATIC, options.out, { recursive: true });
cpSync('tools/demo/gateway.js', join(options.out, 'js/demo-gateway.js'));

write(HOME.page, page(HOME, home(), ''));
for (const item of VIEWS) {
	const { content, scripts } = view(item);
	write(item.page, page(item, content, scripts));
}
