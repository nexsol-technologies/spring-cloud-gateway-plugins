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

package ch.nexsol.gateway.routes.openapi;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OpenAPI-based route definition locator.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.routes-openapi")
public class RoutesOpenapiProperties {

	/**
	 * Whether the OpenAPI-based route definition locator is enabled.
	 */
	private boolean enabled;

	/**
	 * Optional fixed delay between two automatic reloads of every source. When unset the
	 * routes are generated once at startup only.
	 */
	private Duration updateInterval;

	/**
	 * The OpenAPI sources to turn into gateway routes.
	 */
	private List<Source> sources = new ArrayList<>();

	/**
	 * Locations of the documents declaring further sources, read on every reload and
	 * added to the ones configured inline. Anything the resource resolver understands is
	 * accepted: {@code classpath:}, {@code file:} or an {@code http(s)} URL, so a
	 * document served by a Config Server is addressed like any other.
	 */
	private List<String> sourcesLocations = new ArrayList<>();

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Duration getUpdateInterval() {
		return this.updateInterval;
	}

	public void setUpdateInterval(Duration updateInterval) {
		this.updateInterval = updateInterval;
	}

	public List<Source> getSources() {
		return this.sources;
	}

	public void setSources(List<Source> sources) {
		this.sources = sources;
	}

	public List<String> getSourcesLocations() {
		return this.sourcesLocations;
	}

	public void setSourcesLocations(List<String> sourcesLocations) {
		this.sourcesLocations = sourcesLocations;
	}

	/**
	 * A single OpenAPI source and how to turn it into routes.
	 */
	public static class Source {

		/**
		 * Base route id. In {@link RouteGenerationMode#AGGREGATED} mode it is the route
		 * id; in {@link RouteGenerationMode#PER_OPERATION} mode it prefixes the per
		 * operation route ids.
		 */
		private String id;

		/**
		 * Target backend URI the generated routes forward to.
		 */
		private URI uri;

		/**
		 * Location of the OpenAPI document (an {@code http(s)} URL or a file path).
		 */
		private String specUrl;

		/**
		 * How to turn the document into routes.
		 */
		private RouteGenerationMode mode = RouteGenerationMode.AGGREGATED;

		/**
		 * Prefix added, on the gateway side, to every path the contract declares: with
		 * {@code /book-service}, the operation {@code /books} is exposed as
		 * {@code /book-service/books}. The prefix is stripped again before the request is
		 * forwarded, so the backend still receives the path its contract declares. This
		 * is what keeps two contracts declaring the same paths apart; leave it unset to
		 * expose the contract paths as they are.
		 */
		private String pathPrefix;

		/**
		 * Base path prepended (as a {@code PrefixPath} filter) to the backend request,
		 * since the OpenAPI operation paths are relative to the document server base
		 * path. When {@code null} it is derived from the first document server; set it to
		 * force a value, or to an empty string to add no prefix.
		 */
		private String basePath;

		/**
		 * Metadata attached to every generated route.
		 */
		private Map<String, Object> metadata = new LinkedHashMap<>();

		/**
		 * Filters, in shorthand form (for example {@code Retry=3}), attached to every
		 * generated route.
		 */
		private List<String> filters = new ArrayList<>();

		/**
		 * Whether the traffic of the generated routes is also validated against this
		 * contract, by attaching the {@code OpenapiValidation} filter of the
		 * {@code spring-cloud-gateway-openapi-validation} plugin. Generating routes from
		 * a contract does not validate anything by itself; this is what ties the two
		 * together, reusing the {@code spec-url} and {@code path-prefix} declared here so
		 * they cannot drift apart.
		 * <p>
		 * Requires that plugin on the classpath. Leave it off and declare the filter in
		 * {@code filters} by hand to validate against a different document than the one
		 * the routes were generated from.
		 */
		private boolean validate;

		public String getId() {
			return this.id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public URI getUri() {
			return this.uri;
		}

		public void setUri(URI uri) {
			this.uri = uri;
		}

		public String getSpecUrl() {
			return this.specUrl;
		}

		public void setSpecUrl(String specUrl) {
			this.specUrl = specUrl;
		}

		public RouteGenerationMode getMode() {
			return this.mode;
		}

		public void setMode(RouteGenerationMode mode) {
			this.mode = mode;
		}

		public String getPathPrefix() {
			return this.pathPrefix;
		}

		public void setPathPrefix(String pathPrefix) {
			this.pathPrefix = pathPrefix;
		}

		public String getBasePath() {
			return this.basePath;
		}

		public void setBasePath(String basePath) {
			this.basePath = basePath;
		}

		public Map<String, Object> getMetadata() {
			return this.metadata;
		}

		public void setMetadata(Map<String, Object> metadata) {
			this.metadata = metadata;
		}

		public List<String> getFilters() {
			return this.filters;
		}

		public void setFilters(List<String> filters) {
			this.filters = filters;
		}

		public boolean isValidate() {
			return this.validate;
		}

		public void setValidate(boolean validate) {
			this.validate = validate;
		}

	}

}
