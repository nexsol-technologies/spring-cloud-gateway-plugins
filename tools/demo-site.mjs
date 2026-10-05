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
 * Assembles the pages published to GitHub Pages, where the views of the console that move
 * can be watched moving. A README can embed a drawing; it cannot run one.
 *
 * Nothing here is a copy of the console. The stylesheet and the scripts are the module's
 * own files, taken as they are, and the markup of a view is read out of its Thymeleaf
 * template with the th: attributes dropped — so a view changed in the module is a demo
 * changed with it, and there is no second rendering of the console to keep in step. What
 * the demo supplies is the one thing a published page cannot have: a gateway answering.
 * `fetch` is stubbed in the page, over invented traffic that advances on its own.
 *
 * It needs nothing installed, and no build: Node reads files and writes files.
 * See tools/README.md.
 */

import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

const STATIC = 'spring-cloud-gateway-ui/src/main/resources/static';
const TEMPLATES = 'spring-cloud-gateway-ui/src/main/resources/templates/dashboard';

/* The console's own files, at the paths the demo pages reference them by. */
const ASSETS = [
	['css/bootstrap.min.css', `${STATIC}/css/bootstrap.min.css`],
	['css/gateway-ui.css', `${STATIC}/css/gateway-ui.css`],
	['js/gateway-ui.js', `${STATIC}/js/gateway-ui.js`],
	['js/gateway-service-flow.js', `${STATIC}/js/gateway-service-flow.js`]
];

/*
 * A page of the site, and the view whose markup is poured into it. `view` names the template
 * read from the module; the page carries a <!-- flow-view --> comment where it goes.
 */
const PAGES = [
	{ page: 'index.html' },
	{ page: 'flow.html', view: 'service-flow.html', slot: '<!-- flow-view -->' }
];

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

/*
 * The content slot of a view, lifted out of the template the console renders it from.
 *
 * The template is a fragment: it names the shell layout, fills a content slot and a scripts
 * slot, and the shell supplies everything around them. Only the content is wanted here — the
 * scripts slot is a <script> tag the demo page writes itself, since it has to put its own
 * stubbed fetch in front of it.
 */
function viewMarkup(name) {
	const template = readFileSync(join(TEMPLATES, name), 'utf8');
	const opens = template.indexOf('<div id="flow-content">');
	const scripts = template.indexOf('<div id="flow-scripts">');
	if (opens < 0 || scripts < 0) {
		throw new Error(`${name} no longer carries a flow-content and a flow-scripts slot.`);
	}
	let markup = template.slice(opens, template.lastIndexOf('</div>', scripts) + '</div>'.length);

	/*
	 * The view band is the one fragment of the shell a view calls for itself — the title, the
	 * description and the coverage line. The demo page carries its own heading instead, and
	 * the element the view fills with the coverage, which is all its script asks of it.
	 */
	const band = markup.match(/[ \t]*<div th:replace="~\{dashboard\/fragments\/view-band[\s\S]*?><\/div>\n/);
	if (!band) {
		throw new Error(`${name} no longer opens with a view-band fragment.`);
	}
	markup = markup.replace(band[0], '');

	// Thymeleaf attributes are instructions to a server that is not there. Every one of them
	// doubles an attribute the template already carries for exactly this reason: so that the
	// page can be opened without one.
	return markup.replace(/\s+th:[\w-]+="[^"]*"/g, '');
}

function write(path, content) {
	const target = join(options.out, path);
	mkdirSync(dirname(target), { recursive: true });
	writeFileSync(target, content);
	console.log(target);
}

rmSync(options.out, { recursive: true, force: true });

for (const [path, source] of ASSETS) {
	const target = join(options.out, path);
	mkdirSync(dirname(target), { recursive: true });
	cpSync(source, target);
	console.log(target);
}

for (const { page, view, slot } of PAGES) {
	let html = readFileSync(join('tools/demo', page), 'utf8');
	if (view) {
		if (!html.includes(slot)) {
			throw new Error(`tools/demo/${page} no longer carries its ${slot} slot.`);
		}
		html = html.replace(slot, viewMarkup(view));
	}
	write(page, html.replaceAll('{{version}}', options.version));
}
