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

package ch.nexsol.gateway.openapi;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OpenAPI hub.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.hub-openapi")
public class HubOpenapiProperties {

	private final Discovery discovery = new Discovery();

	public Discovery getDiscovery() {
		return this.discovery;
	}

	/**
	 * How the hub probes the discovered services for their OpenAPI document. The defaults
	 * are sized so that a route refresh costs the same whether the registry holds ten
	 * services or several hundred.
	 */
	public static class Discovery {

		/**
		 * Maximum time a single probe may take, connection included. Bounds the route
		 * refresh: without it a service that accepts connections but never answers holds
		 * the whole refresh, and the connection it uses.
		 */
		private Duration timeout = Duration.ofSeconds(2);

		/**
		 * Number of services probed at the same time. This is what keeps a registry
		 * holding hundreds of services from firing hundreds of concurrent requests on
		 * every route refresh.
		 */
		private int concurrency = 16;

		/**
		 * Maximum number of connections of the pool dedicated to the probes. The probes
		 * get their own pool so they never compete for the connections the gateway
		 * proxies its traffic on.
		 */
		private int maxConnections = 50;

		/**
		 * How long the path a document was found at, or the confirmed absence of a
		 * document, is remembered per service instance. A route refresh happens on every
		 * discovery heartbeat, so without this every heartbeat probes every service
		 * again. Set to zero to probe on every refresh.
		 */
		private Duration cacheTtl = Duration.ofMinutes(5);

		public Duration getTimeout() {
			return this.timeout;
		}

		public void setTimeout(Duration timeout) {
			this.timeout = timeout;
		}

		public int getConcurrency() {
			return this.concurrency;
		}

		public void setConcurrency(int concurrency) {
			this.concurrency = concurrency;
		}

		public int getMaxConnections() {
			return this.maxConnections;
		}

		public void setMaxConnections(int maxConnections) {
			this.maxConnections = maxConnections;
		}

		public Duration getCacheTtl() {
			return this.cacheTtl;
		}

		public void setCacheTtl(Duration cacheTtl) {
			this.cacheTtl = cacheTtl;
		}

	}

}
