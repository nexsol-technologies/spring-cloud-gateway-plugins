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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for the route definition locator sourcing JSON/YAML route
 * files served over HTTP by a Spring Cloud Config Server.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.routes-configserver")
public class RoutesConfigServerProperties {

	/**
	 * Whether the Config Server route definition locator is enabled.
	 */
	private boolean enabled;

	/**
	 * Optional fixed delay between two automatic reloads of every source. When unset the
	 * routes are fetched once at startup only.
	 */
	private Duration updateInterval;

	/**
	 * Full URLs pointing at individual JSON/YAML route files, typically the Config Server
	 * plain-text resource endpoint, for example
	 * {@code http://localhost:8888/gateway/default/main/orders-routes.yaml}.
	 */
	private List<String> urls = new ArrayList<>();

	/**
	 * Config Server coordinates used to build the file URLs from a base "directory" and
	 * an explicit list of file names.
	 */
	private ConfigServer configServer = new ConfigServer();

	/**
	 * Largest route file the loader reads. A file is parsed whole, so it is buffered
	 * whole, and this is the ceiling the reactive codecs enforce while it is read. When
	 * unset the client keeps the one the application configured through
	 * {@code spring.http.codecs.max-in-memory-size}, 256&nbsp;KB by default.
	 */
	private DataSize maxResponseSize;

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

	public List<String> getUrls() {
		return this.urls;
	}

	public void setUrls(List<String> urls) {
		this.urls = urls;
	}

	public DataSize getMaxResponseSize() {
		return this.maxResponseSize;
	}

	public void setMaxResponseSize(DataSize maxResponseSize) {
		this.maxResponseSize = maxResponseSize;
	}

	public ConfigServer getConfigServer() {
		return this.configServer;
	}

	public void setConfigServer(ConfigServer configServer) {
		this.configServer = configServer;
	}

	/**
	 * Config Server coordinates addressing route files through the plain-text resource
	 * endpoint ({@code /{name}/{profile}[/{label}]/{path}}). Since the Config Server has
	 * no directory-listing API, the files are enumerated explicitly.
	 */
	public static class ConfigServer {

		/**
		 * Base URI of the Config Server, for example {@code http://localhost:8888}.
		 */
		private String uri;

		/**
		 * Application name, mapped to the {@code {name}} path segment.
		 */
		private String name;

		/**
		 * Profile, mapped to the {@code {profile}} path segment.
		 */
		private String profile = "default";

		/**
		 * Optional label (git branch/tag), mapped to the {@code {label}} path segment.
		 */
		private String label;

		/**
		 * File paths under the coordinate ("directory" content), each resolved against
		 * the plain-text resource endpoint, for example {@code routes/orders.yaml}.
		 */
		private List<String> files = new ArrayList<>();

		public String getUri() {
			return this.uri;
		}

		public void setUri(String uri) {
			this.uri = uri;
		}

		public String getName() {
			return this.name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public String getProfile() {
			return this.profile;
		}

		public void setProfile(String profile) {
			this.profile = profile;
		}

		public String getLabel() {
			return this.label;
		}

		public void setLabel(String label) {
			this.label = label;
		}

		public List<String> getFiles() {
			return this.files;
		}

		public void setFiles(List<String> files) {
			this.files = files;
		}

	}

}
