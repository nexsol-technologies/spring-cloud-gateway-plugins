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
import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for the OpenAPI hub.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.hub-openapi")
public class HubOpenapiProperties {

	private final Discovery discovery = new Discovery();

	private final Security security = new Security();

	/**
	 * Largest aggregated document the hub holds in memory while it rewrites it.
	 * <p>
	 * Pointing the {@code servers} section and the security schemes at the gateway means
	 * parsing the whole document, so it is buffered whole. The reactive codecs stop at
	 * 256&nbsp;KB by default, and a document past that ceiling is answered with a
	 * {@code DataBufferLimitException} and a 500. This ceiling is the hub's own: raising
	 * it leaves what the gateway buffers of its routed traffic
	 * ({@code spring.http.codecs.max-in-memory-size}) untouched.
	 */
	private DataSize maxDocumentSize = DataSize.ofMegabytes(2);

	public Discovery getDiscovery() {
		return this.discovery;
	}

	public Security getSecurity() {
		return this.security;
	}

	public DataSize getMaxDocumentSize() {
		return this.maxDocumentSize;
	}

	public void setMaxDocumentSize(DataSize maxDocumentSize) {
		this.maxDocumentSize = maxDocumentSize;
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

	/**
	 * Which OpenID Connect issuer the aggregated documents advertise.
	 * <p>
	 * A service names the issuer it validates its own traffic against, which is routinely
	 * an address internal to the cluster: the document travels to a browser, where that
	 * address resolves to nothing and the console cannot obtain a token to try the API
	 * with. The gateway, on the other hand, is configured with the issuers it accepts on
	 * the traffic it routes &mdash; and a token good enough for the traffic is exactly
	 * the token an operation needs.
	 */
	public static class Security {

		/**
		 * Where the OpenID Connect issuer advertised by the aggregated documents comes
		 * from.
		 */
		private Issuer issuer = Issuer.DOCUMENT;

		public Issuer getIssuer() {
			return this.issuer;
		}

		public void setIssuer(Issuer issuer) {
			this.issuer = issuer;
		}

		/**
		 * The two things an aggregated document can say about the issuer to authenticate
		 * against.
		 */
		public enum Issuer {

			/**
			 * Leave the document as its service wrote it. The issuer is then whatever the
			 * service validates its own traffic against, internal address included.
			 */
			DOCUMENT,

			/**
			 * Advertise the issuers of the gateway, read from
			 * {@code spring.security.oauth2.resourceserver}: the multi-tenant list, each
			 * tenant under its {@code id}, or the single {@code jwt.issuer-uri}. Every
			 * {@code openIdConnect} security scheme of the document is rewritten to point
			 * at them; with several tenants the document offers one scheme per tenant, so
			 * the console can be authenticated as any of them.
			 * <p>
			 * Falls back to {@link #DOCUMENT} when the gateway is configured with no
			 * issuer at all.
			 */
			GATEWAY

		}

	}

}
