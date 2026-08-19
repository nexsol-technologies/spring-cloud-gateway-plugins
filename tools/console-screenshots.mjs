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
 * Captures the screenshots of the gateway console that the READMEs embed.
 *
 * `chrome --screenshot` was enough while the console was open. It is not any more: a
 * console running with ui.security.mode=authenticated answers the login page to everything,
 * and the command line has no way to carry the session that follows. So this signs in over
 * HTTP, hands the session cookie to Chrome through the DevTools Protocol, and shoots every
 * view in both themes.
 *
 * It needs nothing installed: Node carries the WebSocket, Chrome carries the protocol.
 * See tools/README.md.
 */

import { spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/*
 * The views the READMEs embed, in the order they are read there. `themes` narrows a view to
 * the drawings that are actually published: the collapsed menu is only ever shown light,
 * since what it demonstrates is the width of the menu and not the palette.
 */
const VIEWS = [
	{ name: 'home', path: '/ui' },
	{ name: 'collapsed', path: '/ui', collapsed: true, themes: ['light'] },
	{ name: 'routes', path: '/ui/routes' },
	{ name: 'routes-db', path: '/ui/routes/db' },
	{ name: 'route-tester', path: '/ui/routes/test' },
	{ name: 'traffic', path: '/ui/metrics' },
	{ name: 'instances', path: '/ui/metrics/instances' },
	{ name: 'service-graph', path: '/ui/service-graph' },
	{ name: 'audit', path: '/ui/audit' },
	{ name: 'openapi', path: '/ui/openapi' },
	// Shown to a signed-in visitor holding none of the required roles. It renders for any
	// principal, so it is shot with the same session as the rest.
	{ name: 'forbidden', path: '/ui/forbidden' },
	// The login page is the one view there is no point being signed in for.
	{ name: 'login', path: '/ui/login', anonymous: true }
];

const CHROME_CANDIDATES = [
	'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
	'/Applications/Chromium.app/Contents/MacOS/Chromium',
	'/usr/bin/google-chrome',
	'/usr/bin/chromium'
];

const options = {
	base: 'http://localhost:8181',
	out: 'spring-cloud-gateway-ui/doc',
	user: 'superadmin',
	password: 'superadmin',
	views: '',
	themes: 'light,dark',
	width: '1280',
	height: '860',
	// How long a view is given to draw before it is shot. The traffic chart and the API
	// reference render once their data has arrived, which is what this waits for.
	settle: '4000',
	port: '9222',
	chrome: process.env.CHROME ?? ''
};

for (const argument of process.argv.slice(2)) {
	const [key, value] = argument.replace(/^--/, '').split('=');
	if (!(key in options)) {
		console.error(`Unknown option --${key}. Known: ${Object.keys(options).join(', ')}`);
		process.exit(2);
	}
	options[key] = value ?? 'true';
}

const themes = options.themes.split(',').filter(Boolean);
const wanted = options.views.split(',').filter(Boolean);
const views = wanted.length ? VIEWS.filter((view) => wanted.includes(view.name)) : VIEWS;
if (wanted.length && views.length !== wanted.length) {
	const missing = wanted.filter((name) => !VIEWS.some((view) => view.name === name));
	console.error(`Unknown view(s): ${missing.join(', ')}. Known: ${VIEWS.map((view) => view.name).join(', ')}`);
	process.exit(2);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function cookieValue(headers, name) {
	for (const header of headers.getSetCookie()) {
		const [pair] = header.split(';');
		const [key, ...rest] = pair.split('=');
		if (key.trim() === name) {
			return rest.join('=');
		}
	}
	return null;
}

/*
 * Returns the session of a signed-in operator, or null when the console is open and there
 * is nothing to sign into. The authentication changes the session id, so what is kept is
 * the cookie the POST handed back and not the one the login page was served under.
 */
async function signIn() {
	const console_ = await fetch(`${options.base}/ui`, { redirect: 'manual' });
	if (console_.status === 200) {
		return null;
	}
	const page = await fetch(`${options.base}/ui/login`, { redirect: 'manual' });
	if (page.status !== 200) {
		throw new Error(`${options.base}/ui/login answered ${page.status}: is the gateway running?`);
	}
	const html = await page.text();
	const token = /name="_csrf" value="([^"]+)"/.exec(html);
	const form = new URLSearchParams({ username: options.user, password: options.password });
	if (token) {
		form.set('_csrf', token[1]);
	}
	const session = cookieValue(page.headers, 'SESSION');
	const signedIn = await fetch(`${options.base}/ui/login`, {
		method: 'POST',
		redirect: 'manual',
		headers: { cookie: `SESSION=${session}`, 'content-type': 'application/x-www-form-urlencoded' },
		body: form
	});
	const location = signedIn.headers.get('location');
	if (signedIn.status !== 302 || location?.includes('error')) {
		throw new Error(`Signing in as ${options.user} failed (${signedIn.status} ${location ?? ''}): check --user and --password.`);
	}
	return cookieValue(signedIn.headers, 'SESSION') ?? session;
}

function chromeCommand() {
	if (options.chrome) {
		return options.chrome;
	}
	const found = CHROME_CANDIDATES.find((candidate) => {
		try {
			return statSync(candidate).isFile();
		}
		catch {
			return false;
		}
	});
	if (!found) {
		throw new Error(`No Chrome found. Pass --chrome=<path> or set CHROME.`);
	}
	return found;
}

async function connect(port) {
	// Chrome takes a moment to open its debugging port; there is nothing to listen for.
	for (let attempt = 0; attempt < 40; attempt++) {
		try {
			const version = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
			const socket = new WebSocket(version.webSocketDebuggerUrl);
			await new Promise((done) => socket.addEventListener('open', done, { once: true }));
			return socket;
		}
		catch {
			await sleep(500);
		}
	}
	throw new Error(`Chrome did not open its debugging port on ${port}.`);
}

function protocol(socket) {
	let nextId = 0;
	const pending = new Map();
	const waiters = [];
	socket.addEventListener('message', (event) => {
		const message = JSON.parse(event.data);
		if (message.id !== undefined) {
			pending.get(message.id)?.(message);
			pending.delete(message.id);
			return;
		}
		const index = waiters.findIndex((waiter) => waiter.method === message.method && waiter.sessionId === message.sessionId);
		if (index >= 0) {
			waiters.splice(index, 1)[0].resolve(message);
		}
	});
	return {
		send(method, params = {}, sessionId) {
			const id = ++nextId;
			return new Promise((resolve, reject) => {
				pending.set(id, (message) =>
					message.error ? reject(new Error(`${method}: ${message.error.message}`)) : resolve(message.result));
				socket.send(JSON.stringify({ id, method, params, sessionId }));
			});
		},
		once(method, sessionId) {
			return new Promise((resolve) => waiters.push({ method, sessionId, resolve }));
		}
	};
}

const session = await signIn();
console.log(session ? `Signed in as ${options.user}.` : 'The console is open, no session needed.');

mkdirSync(options.out, { recursive: true });
const profile = mkdtempSync(join(tmpdir(), 'gateway-console-shots-'));
const chrome = spawn(chromeCommand(), [
	'--headless=new',
	'--disable-gpu',
	'--hide-scrollbars',
	`--user-data-dir=${profile}`,
	`--remote-debugging-port=${options.port}`,
	`--window-size=${options.width},${options.height}`,
	'about:blank'
], { stdio: 'ignore' });

let failure = null;
try {
	const socket = await connect(options.port);
	const cdp = protocol(socket);
	for (const theme of themes) {
		const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
		const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
		await cdp.send('Page.enable', {}, sessionId);
		await cdp.send('Network.enable', {}, sessionId);
		await cdp.send('Runtime.enable', {}, sessionId);
		await cdp.send('Emulation.setDeviceMetricsOverride',
			{ width: Number(options.width), height: Number(options.height), deviceScaleFactor: 1, mobile: false }, sessionId);
		// The shell reads the system preference when nothing was stored, so emulating the
		// media feature is enough to draw either theme.
		await cdp.send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }] }, sessionId);

		for (const view of views) {
			if (view.themes && !view.themes.includes(theme)) {
				continue;
			}
			await cdp.send('Network.clearBrowserCookies', {}, sessionId);
			if (session && !view.anonymous) {
				await cdp.send('Network.setCookie',
					{ name: 'SESSION', value: session, url: options.base }, sessionId);
			}
			const loaded = cdp.once('Page.loadEventFired', sessionId);
			await cdp.send('Page.navigate', { url: `${options.base}${view.path}` }, sessionId);
			await loaded;
			if (view.collapsed) {
				// The side menu remembers its state, so it is set and the page comes back
				// with it already applied rather than animating into it.
				await cdp.send('Runtime.evaluate', { expression: "localStorage.setItem('gw-sidebar-collapsed', 'true')" }, sessionId);
				const again = cdp.once('Page.loadEventFired', sessionId);
				await cdp.send('Page.reload', {}, sessionId);
				await again;
			}
			await sleep(Number(options.settle));
			const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' }, sessionId);
			const file = join(options.out, `${view.name}-${theme}.png`);
			writeFileSync(file, Buffer.from(data, 'base64'));
			console.log(file);
			if (view.collapsed) {
				await cdp.send('Runtime.evaluate', { expression: "localStorage.removeItem('gw-sidebar-collapsed')" }, sessionId);
			}
		}
		await cdp.send('Target.closeTarget', { targetId });
	}
	socket.close();
}
catch (error) {
	failure = error;
}
finally {
	chrome.kill();
	// Chrome keeps writing its profile for a moment after the signal, and removing it from
	// under itself fails. Give it a beat, and treat a temporary directory left behind as
	// what it is: nothing worth failing a capture over.
	await Promise.race([new Promise((resolve) => chrome.once('exit', resolve)), sleep(3000)]);
	try {
		rmSync(profile, { recursive: true, force: true });
	}
	catch {
		// Left in the temporary directory the operating system empties on its own.
	}
}

if (failure) {
	console.error(failure.message);
	process.exit(1);
}
