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
 * Gathers the READMEs of the repository into the source tree MkDocs builds the documentation
 * site from (tools/mkdocs.yml), at the paths they have in the repository.
 *
 * A README links to the other READMEs, which the site carries, to its screenshots, which are
 * copied next to it, and to source files, which the site does not carry: those links are
 * pointed at the file on GitHub instead. A link to a file that does not exist fails the run.
 *
 * Only the files git tracks are read: an IDE leaves copies of the samples' READMEs under bin/.
 * See tools/README.md.
 */

import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, posix } from 'node:path';

const REPOSITORY = 'https://github.com/nexsol-technologies/spring-cloud-gateway-plugins';
const IMAGE = /\.(png|svg|gif|jpe?g)$/i;

const options = {
	out: 'target/docs-src'
};

for (const argument of process.argv.slice(2)) {
	const [key, value] = argument.replace(/^--/, '').split('=');
	if (!(key in options)) {
		console.error(`Unknown option --${key}. Known: ${Object.keys(options).join(', ')}`);
		process.exit(2);
	}
	options[key] = value ?? 'true';
}

const readmes = new Set(execFileSync('git', ['ls-files', '*README.md'], { encoding: 'utf8' }).split('\n').filter(Boolean));

function copy(path) {
	const target = join(options.out, path);
	mkdirSync(dirname(target), { recursive: true });
	cpSync(path, target);
}

/* The target a link of `readme` should carry on the site. */
function rewrite(readme, link) {
	if (/^([a-z]+:|#)/i.test(link)) {
		return link;
	}
	const [path, anchor] = link.split(/(?=#)/);
	const resolved = posix.normalize(posix.join(posix.dirname(readme), path));
	if (resolved.startsWith('..') || !existsSync(resolved)) {
		throw new Error(`${readme} links to ${link}, which does not exist.`);
	}
	const directory = statSync(resolved).isDirectory();
	if (readmes.has(resolved)) {
		return link;
	}
	if (directory && readmes.has(posix.join(resolved, 'README.md'))) {
		return `${posix.join(path, 'README.md')}${anchor ?? ''}`;
	}
	if (IMAGE.test(resolved)) {
		copy(resolved);
		return link;
	}
	return `${REPOSITORY}/${directory ? 'tree' : 'blob'}/main/${resolved}${anchor ?? ''}`;
}

rmSync(options.out, { recursive: true, force: true });

for (const readme of readmes) {
	const markdown = readFileSync(readme, 'utf8')
		.replace(/(\]\()([^)\s]+)(\))/g, (_, open, link, close) => open + rewrite(readme, link) + close)
		.replace(/(src=")([^"]+)(")/g, (_, open, link, close) => open + rewrite(readme, link) + close);
	const target = join(options.out, readme);
	mkdirSync(dirname(target), { recursive: true });
	writeFileSync(target, markdown);
	console.log(target);
}
