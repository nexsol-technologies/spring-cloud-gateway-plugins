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
 * Scalar is handed the addresses of the contracts, so it fetches and parses only the one
 * on screen, whichever format it is served in.
 *
 * The vendor extensions Scalar does not know about are rendered by a plugin, from the
 * mapping of extension name to label the page carries. The plugin registry matches an
 * extension by its exact name, so an undeclared extension is not rendered.
 */
(function () {
	'use strict';

	var POLL_MS = 15000;

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

	/** The extensions to render, keyed by name, each with the label it reads under. */
	var extensions = (function () {
		try {
			return JSON.parse(mount.dataset.extensionLabels || '{}');
		}
		catch (ignored) {
			return {};
		}
	})();

	function readable(value) {
		if (Array.isArray(value)) {
			return value
				.map(function (item) {
					return item !== null && typeof item === 'object' ? JSON.stringify(item) : item;
				})
				.join(', ');
		}
		if (value !== null && typeof value === 'object') {
			return JSON.stringify(value);
		}
		return String(value);
	}

	/**
	 * The component Scalar renders for one extension.
	 *
	 * The value is read off the attributes: Vue camel-cases declared prop names, so
	 * `x-roles` would be looked up as `xRoles`. The render function returns a string
	 * because the standalone bundle ships no template compiler.
	 */
	function extensionComponent(name, label) {
		return {
			inheritAttrs: false,
			render: function () {
				return label + ' — ' + readable(this.$attrs[name]);
			}
		};
	}

	/** Scalar builds the plugin by calling it, so the registry is handed this factory. */
	function extensionsPlugin() {
		return {
			name: 'gateway-ui-extensions',
			extensions: Object.keys(extensions).map(function (name) {
				return { name: name, component: extensionComponent(name, extensions[name] || name) };
			})
		};
	}

	function configuration(sources) {
		return {
			// The agent flag is read off the active source, so it is set on each of them
			// as well as on the page.
			sources: sources.map(function (source) {
				return Object.assign({ agent: { disabled: true } }, source);
			}),
			// Everything this page loads is served by the gateway itself: no font from an
			// external CDN, so the view still works on an isolated network.
			withDefaultFonts: false,
			darkMode: window.gatewayUi.theme() === 'dark',
			hideDarkModeToggle: true,
			// Scalar enables its AI agent by itself when the page is served from localhost.
			// Its control is a form next to the search box, and it captures the clicks of
			// its own area.
			agent: { disabled: true },
			// On by default. The gateway this console documents may be the only host it is
			// allowed to reach.
			telemetry: false,
			// The search modal opens with its input focused but stays invisible: it carries
			// the utility class `opacity-0`, and the animation revealing it is declared in
			// a stylesheet the bundle does not inject in this integration. Remove once the
			// bundle ships those styles.
			customCss: '.scalar-modal-layout, .scalar-modal { opacity: 1 !important; }',
			plugins: [extensionsPlugin]
		};
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
		render(descriptors);
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

	// Restored before the first load, which polls or not according to it.
	window.gatewayUi.remember(auto);

	load();
	if (!auto || auto.checked) {
		startPolling();
	}
})();
