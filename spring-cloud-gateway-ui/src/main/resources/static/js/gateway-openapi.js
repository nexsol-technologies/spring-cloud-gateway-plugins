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
 * Scalar is handed the addresses of the contracts, not their content: it then fetches only
 * the one on screen, and each contract is fetched and parsed once, by Scalar itself, which
 * is what keeps the view fluid and handles the contracts SpringDoc serves as YAML.
 *
 * The vendor extensions a service documents of its own — the roles a resource requires, the
 * version it appeared in — are displayed through a Scalar plugin: Scalar renders the handful
 * of extensions it knows about and drops the rest, and a plugin is where it lets a page take
 * over the ones it does not know. Each extension is declared in the configuration of the
 * gateway, which is what the page carries: the plugin registry matches an extension by its
 * exact name, so an undeclared extension is not rendered.
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

	/**
	 * The extensions to display, keyed by name, each with the label it reads under. They
	 * are declared in the configuration of the gateway and carried by the page.
	 */
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
	 * The value is read from the attributes rather than from a declared prop: Vue camel-cases
	 * the name of a declared prop, so `x-roles` would be looked up as `xRoles` and the value
	 * would be lost. Returning a string from `render` is deliberate too — the standalone
	 * bundle ships no template compiler, so a template string could not be compiled.
	 */
	function extensionComponent(name, label) {
		return {
			inheritAttrs: false,
			render: function () {
				return label + ' — ' + readable(this.$attrs[name]);
			}
		};
	}

	/** Scalar calls the plugin to build it, hence a factory rather than an object. */
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
			sources: sources,
			// Everything this page loads is served by the gateway itself: no font from an
			// external CDN, so the view still works on an isolated network.
			withDefaultFonts: false,
			darkMode: false,
			hideDarkModeToggle: true,
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

	load();
	if (!auto || auto.checked) {
		startPolling();
	}
})();
