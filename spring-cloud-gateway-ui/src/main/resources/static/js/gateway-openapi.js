/*
 * Renders the aggregated OpenAPI contracts with Scalar.
 *
 * The list of contracts is the one SpringDoc exposes on its swagger-config endpoint, which
 * the OpenAPI hub keeps in sync with the discovered services. That list changes while the
 * page is open — a service registers, another one goes away — so it is polled, and Scalar
 * is only re-configured when the list actually changed: an unchanged poll leaves the page
 * exactly as the reader left it. When the hub aggregated nothing, the contract of the
 * gateway itself is shown, so the view is never empty for no reason.
 *
 * Vendor extensions are folded into the descriptions before Scalar is handed the contract:
 * Scalar only renders the handful of extensions it knows about, so anything a service
 * documents of its own (the Keycloak roles a resource requires, for one) would otherwise be
 * dropped silently at render time.
 */
(function () {
	'use strict';

	var POLL_MS = 15000;

	/** The operation keys of a path item, as opposed to its parameters, servers or $ref. */
	var METHODS = ['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'];

	/**
	 * Extensions Scalar renders on its own, plus its own `x-scalar-` namespace: folding
	 * those into a description would show them twice. Kept in step with the bundled
	 * version, which is stated at the top of scalar.standalone.js.
	 */
	var NATIVE = ['x-internal', 'x-displayName', 'x-badges', 'x-codeSamples', 'x-code-samples',
		'x-tagGroups', 'x-enumDescriptions', 'x-enum-descriptions', 'x-enumNames', 'x-enum-varnames',
		'x-example', 'x-examples', 'x-additionalPropertiesName'];

	var mount = document.getElementById('gw-openapi');
	var error = document.getElementById('gw-openapi-error');
	var count = document.getElementById('gw-openapi-count');
	var refresh = document.getElementById('gw-openapi-refresh');
	var auto = document.getElementById('gw-openapi-auto');
	if (!mount) {
		return;
	}

	var instance = null;
	var signature = null;
	var pollTimer = null;

	function configuration(sources) {
		return {
			sources: sources,
			// Everything this page loads is served by the gateway itself: no font from an
			// external CDN, so the view still works on an isolated network.
			withDefaultFonts: false,
			darkMode: false,
			hideDarkModeToggle: true
		};
	}

	function foldable(key) {
		return key.indexOf('x-') === 0 && key.indexOf('x-scalar-') !== 0 && NATIVE.indexOf(key) === -1;
	}

	function markdown(value) {
		if (Array.isArray(value)) {
			return value
				.map(function (item) {
					return '`' + (item !== null && typeof item === 'object' ? JSON.stringify(item) : item) + '`';
				})
				.join(', ');
		}
		if (value !== null && typeof value === 'object') {
			return '\n\n```json\n' + JSON.stringify(value, null, 2) + '\n```';
		}
		return '`' + value + '`';
	}

	/**
	 * Renders the extensions of a node as the Markdown lines appended to a description,
	 * leaving out the keys the caller already renders from somewhere more specific.
	 */
	function lines(node, skip) {
		if (!node || typeof node !== 'object') {
			return '';
		}
		return Object.keys(node)
			.filter(foldable)
			.filter(function (key) {
				return !skip || skip.indexOf(key) === -1;
			})
			.map(function (key) {
				return '**' + key + '** — ' + markdown(node[key]);
			})
			.join('\n\n');
	}

	/** Appends the rendered extensions to the description the target already carries. */
	function append(target, rendered) {
		if (!target || typeof target !== 'object' || !rendered) {
			return;
		}
		target.description = (target.description ? target.description + '\n\n' : '') + rendered;
	}

	/**
	 * Moves every extension of the contract into the description of the node that carries
	 * it, which is the only part of the document Scalar renders as Markdown.
	 *
	 * The root carries no description of its own, so its extensions land on `info`. A path
	 * item is not rendered as such either: its extensions are repeated on each of its
	 * operations, where they belong from the reader's point of view.
	 */
	function foldExtensions(contract) {
		if (!contract || typeof contract !== 'object') {
			return contract;
		}
		if (contract.info) {
			append(contract.info, lines(contract));
		}
		Object.keys(contract.paths || {}).forEach(function (path) {
			var item = contract.paths[path];
			METHODS.forEach(function (method) {
				var operation = item ? item[method] : null;
				if (operation) {
					// An extension the operation declares itself wins over the one of its
					// path item, rather than being shown twice with two values.
					var shared = lines(item, Object.keys(operation));
					append(operation, [shared, lines(operation)].filter(Boolean).join('\n\n'));
				}
			});
		});
		var schemas = (contract.components || {}).schemas || {};
		Object.keys(schemas).forEach(function (name) {
			append(schemas[name], lines(schemas[name]));
		});
		return contract;
	}

	function fail() {
		mount.style.display = 'none';
		if (error) {
			error.style.display = '';
		}
	}

	function apply(descriptors) {
		if (!descriptors.length) {
			if (!instance) {
				fail();
			}
			return;
		}
		// The signature covers the list of contracts, not their content: an unchanged poll
		// must neither re-render the page nor re-download every document.
		var next = JSON.stringify(descriptors);
		if (next === signature) {
			return;
		}
		if (!window.Scalar || !window.Scalar.createApiReference) {
			fail();
			return;
		}
		signature = next;
		if (count) {
			count.textContent = descriptors.length + (descriptors.length > 1 ? ' contracts' : ' contract');
		}
		Promise.all(descriptors.map(read)).then(render);
	}

	/**
	 * Reads a contract so its extensions can be folded in before rendering. One that cannot
	 * be read as JSON is handed to Scalar by URL, exactly as it was before.
	 */
	function read(descriptor) {
		return fetch(descriptor.url, { headers: { Accept: 'application/json' }, cache: 'no-store' })
			.then(function (response) {
				return response.ok ? response.json() : null;
			})
			.then(function (contract) {
				return contract ? { title: descriptor.title, content: foldExtensions(contract) } : descriptor;
			})
			.catch(function () {
				return descriptor;
			});
	}

	function render(sources) {
		if (instance && instance.updateConfiguration) {
			instance.updateConfiguration(configuration(sources));
			return;
		}
		mount.style.display = '';
		if (error) {
			error.style.display = 'none';
		}
		instance = window.Scalar.createApiReference(mount, configuration(sources));
	}

	function fallbackSources() {
		var documentUrl = mount.dataset.documentUrl;
		return documentUrl ? [{ title: 'Gateway', url: documentUrl }] : [];
	}

	function load() {
		var configUrl = mount.dataset.configUrl;
		if (!configUrl) {
			apply(fallbackSources());
			return;
		}
		fetch(configUrl, { headers: { Accept: 'application/json' }, cache: 'no-store' })
			.then(function (response) {
				return response.ok ? response.json() : null;
			})
			.then(function (config) {
				var urls = config && Array.isArray(config.urls) ? config.urls : [];
				var sources = urls
					.filter(function (entry) {
						return entry && entry.url;
					})
					.map(function (entry) {
						return { title: entry.name || entry.url, url: entry.url };
					});
				apply(sources.length ? sources : fallbackSources());
			})
			.catch(function () {
				apply(fallbackSources());
			});
	}

	function startPolling() {
		if (!pollTimer) {
			pollTimer = setInterval(load, POLL_MS);
		}
	}

	function stopPolling() {
		clearInterval(pollTimer);
		pollTimer = null;
	}

	if (refresh) {
		refresh.addEventListener('click', load);
	}
	if (auto) {
		auto.addEventListener('change', function () {
			return auto.checked ? startPolling() : stopPolling();
		});
	}

	load();
	if (!auto || auto.checked) {
		startPolling();
	}
})();
