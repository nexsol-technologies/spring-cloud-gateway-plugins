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

package ch.nexsol.gateway.metrics.prometheus;

import java.time.Duration;

/**
 * Configuration properties for the Prometheus metrics source, bound under
 * {@code spring.cloud.gateway.server.webflux.metrics.prometheus}.
 */
public class PrometheusMetricsProperties {

	/**
	 * Base name of the gateway request timer as Prometheus stores it. The Micrometer
	 * Prometheus registry renames {@code spring.cloud.gateway.requests} to this, and
	 * exposes it as the {@code _count}, {@code _sum} and {@code _max} series this source
	 * reads.
	 */
	private String meter = "spring_cloud_gateway_requests_seconds";

	/**
	 * Base URL of the Prometheus server, for example {@code http://prometheus:9090}.
	 */
	private String url;

	/**
	 * Extra label matchers restricting the series to this gateway, without the enclosing
	 * braces, for example {@code job="gateway",namespace="prod"}.
	 * <p>
	 * Leave it empty only when this gateway is the sole publisher of the meter: a shared
	 * Prometheus otherwise mixes the traffic of every gateway into one figure, which is
	 * wrong in a way nothing on the page would reveal.
	 */
	private String selector = "";

	/**
	 * How long to wait for Prometheus before reporting no data.
	 */
	private Duration timeout = Duration.ofSeconds(5);

	/**
	 * User name of the Basic credentials sent to Prometheus. Set it together with the
	 * password; leave both unset when the server needs no Basic authentication.
	 */
	private String username;

	/**
	 * Password of the Basic credentials sent to Prometheus.
	 */
	private String password;

	/**
	 * Bearer token sent to Prometheus, for a server that authenticates with one (Thanos,
	 * Mimir, the OpenShift monitoring stack). Ignored when Basic credentials are set.
	 * <p>
	 * The token is read once, at startup. A token that rotates &mdash; a Kubernetes
	 * service account token, for instance &mdash; needs the application to declare its
	 * own {@code prometheusMetricsWebClient} bean instead.
	 */
	private String token;

	/**
	 * @return the Prometheus meter base name
	 */
	public String getMeter() {
		return this.meter;
	}

	/**
	 * @param meter the Prometheus meter base name
	 */
	public void setMeter(String meter) {
		this.meter = meter;
	}

	/**
	 * @return the Prometheus base URL
	 */
	public String getUrl() {
		return this.url;
	}

	/**
	 * @param url the Prometheus base URL
	 */
	public void setUrl(String url) {
		this.url = url;
	}

	/**
	 * @return the extra label matchers
	 */
	public String getSelector() {
		return this.selector;
	}

	/**
	 * @param selector the extra label matchers
	 */
	public void setSelector(String selector) {
		this.selector = selector;
	}

	/**
	 * @return the query timeout
	 */
	public Duration getTimeout() {
		return this.timeout;
	}

	/**
	 * @param timeout the query timeout
	 */
	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	/**
	 * @return the Basic user name
	 */
	public String getUsername() {
		return this.username;
	}

	/**
	 * @param username the Basic user name
	 */
	public void setUsername(String username) {
		this.username = username;
	}

	/**
	 * @return the Basic password
	 */
	public String getPassword() {
		return this.password;
	}

	/**
	 * @param password the Basic password
	 */
	public void setPassword(String password) {
		this.password = password;
	}

	/**
	 * @return the bearer token
	 */
	public String getToken() {
		return this.token;
	}

	/**
	 * @param token the bearer token
	 */
	public void setToken(String token) {
		this.token = token;
	}

}
