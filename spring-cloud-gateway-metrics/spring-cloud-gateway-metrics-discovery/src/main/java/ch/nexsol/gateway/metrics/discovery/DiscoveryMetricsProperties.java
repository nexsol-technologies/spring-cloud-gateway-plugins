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

package ch.nexsol.gateway.metrics.discovery;

import java.time.Duration;

/**
 * Configuration properties for the service discovery metrics source, bound under
 * {@code spring.cloud.gateway.server.webflux.metrics.discovery}.
 */
public class DiscoveryMetricsProperties {

	/**
	 * Path each instance serves its own figures on. Every instance answers it with its
	 * local meter registry and never with the consolidated view, which is what keeps the
	 * fan-out from calling itself in a loop.
	 */
	public static final String DEFAULT_PATH = "/ui/metrics/local";

	/**
	 * Id of this gateway in the service registry, used to discover the sibling instances.
	 * Defaults to {@code spring.application.name}.
	 */
	private String serviceId;

	/**
	 * Path the sibling instances are polled on.
	 */
	private String path = DEFAULT_PATH;

	/**
	 * How long to wait for a sibling before leaving it out of the figures.
	 */
	private Duration timeout = Duration.ofSeconds(3);

	/**
	 * @return the service id of this gateway
	 */
	public String getServiceId() {
		return this.serviceId;
	}

	/**
	 * @param serviceId the service id of this gateway
	 */
	public void setServiceId(String serviceId) {
		this.serviceId = serviceId;
	}

	/**
	 * @return the path the siblings are polled on
	 */
	public String getPath() {
		return this.path;
	}

	/**
	 * @param path the path the siblings are polled on
	 */
	public void setPath(String path) {
		this.path = path;
	}

	/**
	 * @return the per-instance timeout
	 */
	public Duration getTimeout() {
		return this.timeout;
	}

	/**
	 * @param timeout the per-instance timeout
	 */
	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

}
