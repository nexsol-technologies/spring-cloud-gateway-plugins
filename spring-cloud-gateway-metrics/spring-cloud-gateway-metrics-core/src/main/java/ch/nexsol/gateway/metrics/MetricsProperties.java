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

package ch.nexsol.gateway.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the route metrics plugin, bound under
 * {@code spring.cloud.gateway.server.webflux.metrics}.
 */
public class MetricsProperties {

	/**
	 * Default exclusion: the documentation routes the OpenAPI hub publishes. They carry
	 * contracts, not traffic, and their volume says nothing about how the gateway is
	 * used.
	 */
	public static final String OPENAPI_DOCS_PATTERN = "openapi-docs-.*";

	/**
	 * Whether the metrics plugin is active. When {@code false} no source is registered
	 * and the traffic view stays closed.
	 */
	private boolean enabled = true;

	/**
	 * Selected metrics source. Read by the provider modules (for example
	 * {@code prometheus}, {@code redis} or {@code discovery}) to activate their source.
	 * When unset, the figures of the running instance are reported.
	 */
	private String provider;

	/**
	 * Identifier of this instance, shown next to the figures it produced. Defaults to the
	 * host name, which is the pod name on Kubernetes.
	 */
	private String instanceId;

	/**
	 * Regular expressions matched against the route id: a route matching any of them is
	 * left out of the figures. The whole id must match. Set to an empty list to report
	 * every route.
	 */
	private List<String> excludedRoutes = new ArrayList<>(List.of(OPENAPI_DOCS_PATTERN));

	/**
	 * Configuration of the per-instance figures.
	 */
	private Instance instance = new Instance();

	/**
	 * @return whether the metrics plugin is enabled
	 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * @param enabled whether the metrics plugin is enabled
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * @return the selected metrics source
	 */
	public String getProvider() {
		return this.provider;
	}

	/**
	 * @param provider the selected metrics source
	 */
	public void setProvider(String provider) {
		this.provider = provider;
	}

	/**
	 * @return the configured instance identifier
	 */
	public String getInstanceId() {
		return this.instanceId;
	}

	/**
	 * @param instanceId the instance identifier
	 */
	public void setInstanceId(String instanceId) {
		this.instanceId = instanceId;
	}

	/**
	 * @return the route id exclusion patterns
	 */
	public List<String> getExcludedRoutes() {
		return this.excludedRoutes;
	}

	/**
	 * @param excludedRoutes the route id exclusion patterns
	 */
	public void setExcludedRoutes(List<String> excludedRoutes) {
		this.excludedRoutes = excludedRoutes;
	}

	/**
	 * @return the per-instance figures configuration
	 */
	public Instance getInstance() {
		return this.instance;
	}

	/**
	 * @param instance the per-instance figures configuration
	 */
	public void setInstance(Instance instance) {
		this.instance = instance;
	}

	/**
	 * Configuration of the per-instance technical figures.
	 */
	public static class Instance {

		/**
		 * Whether the per-instance figures are collected. When {@code false} no source is
		 * registered and the instances view stays closed. Independent from the route
		 * figures: turning one off without the other is a legitimate need both ways
		 * round.
		 */
		private boolean enabled = true;

		/**
		 * Whether the gateway HTTP client is instrumented, which is what publishes the
		 * event loop and HTTP client counters. Reactor Netty only registers them once the
		 * transport carries a metrics recorder, and the gateway never asks for one.
		 * <p>
		 * Off by default: the recorder adds a handler to the pipeline of every
		 * connection, so it costs something on the data path of the gateway. Turning that
		 * on is the operator's call, not an observation plugin's.
		 * <p>
		 * The connection pool counters are governed separately by
		 * {@code spring.cloud.gateway.server.webflux.httpclient.pool.metrics}, read too
		 * early for any bean to influence it.
		 */
		private boolean instrumentHttpClient;

		/**
		 * @return whether the per-instance figures are collected
		 */
		public boolean isEnabled() {
			return this.enabled;
		}

		/**
		 * @param enabled whether the per-instance figures are collected
		 */
		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		/**
		 * @return whether the gateway HTTP client is instrumented
		 */
		public boolean isInstrumentHttpClient() {
			return this.instrumentHttpClient;
		}

		/**
		 * @param instrumentHttpClient whether the gateway HTTP client is instrumented
		 */
		public void setInstrumentHttpClient(boolean instrumentHttpClient) {
			this.instrumentHttpClient = instrumentHttpClient;
		}

	}

}
