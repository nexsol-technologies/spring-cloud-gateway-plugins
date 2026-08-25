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

package ch.nexsol.gateway.routes.configserver;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.routes.configserver.RoutesConfigServerProperties.ConfigServer;
import ch.nexsol.gateway.routes.files.RouteDefinitionFileParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Fetches JSON/YAML route files served over HTTP by a Spring Cloud Config Server and
 * parses them into {@link RouteDefinition}. The fetch is non-blocking through
 * {@link WebClient}; parsing reuses the format shared with the file-based locator.
 * <p>
 * The effective list of file URLs is the union of the explicitly configured full URLs and
 * the URLs built from the Config Server coordinates (base URI, application name, profile,
 * optional label) and the explicit list of files, resolved against the Config Server
 * plain-text resource endpoint {@code /{name}/{profile}[/{label}]/{path}}.
 */
public class ConfigServerRouteDefinitionLoader {

	private static final Logger LOG = LoggerFactory.getLogger(ConfigServerRouteDefinitionLoader.class);

	private final WebClient webClient;

	private final RouteDefinitionFileParser parser;

	private final RoutesConfigServerProperties properties;

	/**
	 * Creates the loader.
	 * @param webClient the client used to fetch the files
	 * @param parser the JSON/YAML route file parser
	 * @param properties the Config Server locator properties
	 */
	public ConfigServerRouteDefinitionLoader(WebClient webClient, RouteDefinitionFileParser parser,
			RoutesConfigServerProperties properties) {
		this.webClient = webClient;
		this.parser = parser;
		this.properties = properties;
		// Resolved once here for its validation alone: incomplete coordinates must keep
		// failing the context at startup rather than silently at the first fetch.
		resolveUrls(properties);
	}

	/**
	 * Fetches and parses every configured route file, preserving the configured order.
	 * <p>
	 * The URLs are resolved on every subscription rather than once at construction:
	 * {@code /actuator/refresh} re-binds the properties bean in place, so a file added to
	 * {@code config-server.files} is picked up without restarting the gateway.
	 * @return the aggregated route definitions
	 */
	public Flux<RouteDefinition> load() {
		return Flux.defer(() -> {
			List<String> urls = resolveUrls(this.properties);
			if (urls.isEmpty()) {
				LOG.warn("No Config Server route file URLs configured");
				return Flux.<RouteDefinition>empty();
			}
			return Flux.fromIterable(urls).concatMap(this::fetch);
		});
	}

	private Flux<RouteDefinition> fetch(String url) {
		return this.webClient.get()
			.uri(url)
			.retrieve()
			.bodyToMono(byte[].class)
			.map((body) -> parse(url, body))
			.flatMapMany(Flux::fromIterable)
			.onErrorMap((ex) -> !(ex instanceof RouteConfigServerException),
					(ex) -> new RouteConfigServerException("Unable to load route file '" + url + "'", ex));
	}

	private List<RouteDefinition> parse(String url, byte[] body) {
		return this.parser.parse(filenameOf(url), new ByteArrayInputStream(body));
	}

	/**
	 * Builds the effective list of file URLs from the explicit URLs and the Config Server
	 * coordinates.
	 * @param properties the Config Server locator properties
	 * @return the ordered, immutable list of URLs to fetch
	 */
	static List<String> resolveUrls(RoutesConfigServerProperties properties) {
		List<String> resolved = new ArrayList<>(properties.getUrls());
		ConfigServer configServer = properties.getConfigServer();
		if (configServer != null && !configServer.getFiles().isEmpty()) {
			resolved.addAll(buildConfigServerUrls(configServer));
		}
		return List.copyOf(resolved);
	}

	private static List<String> buildConfigServerUrls(ConfigServer configServer) {
		if (!StringUtils.hasText(configServer.getUri())) {
			throw new RouteConfigServerException(
					"'config-server.uri' must be set when 'config-server.files' is provided");
		}
		if (!StringUtils.hasText(configServer.getName())) {
			throw new RouteConfigServerException(
					"'config-server.name' must be set when 'config-server.files' is provided");
		}
		String base = trimTrailingSlash(configServer.getUri());
		String profile = StringUtils.hasText(configServer.getProfile()) ? configServer.getProfile() : "default";
		List<String> urls = new ArrayList<>();
		for (String file : configServer.getFiles()) {
			StringBuilder url = new StringBuilder(base).append('/')
				.append(configServer.getName())
				.append('/')
				.append(profile);
			if (StringUtils.hasText(configServer.getLabel())) {
				url.append('/').append(configServer.getLabel());
			}
			url.append('/').append(trimLeadingSlash(file));
			urls.add(url.toString());
		}
		return urls;
	}

	private static String filenameOf(String url) {
		String path = URI.create(url).getPath();
		if (!StringUtils.hasText(path)) {
			return url;
		}
		int slash = path.lastIndexOf('/');
		return (slash >= 0) ? path.substring(slash + 1) : path;
	}

	private static String trimTrailingSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private static String trimLeadingSlash(String value) {
		return value.startsWith("/") ? value.substring(1) : value;
	}

}
