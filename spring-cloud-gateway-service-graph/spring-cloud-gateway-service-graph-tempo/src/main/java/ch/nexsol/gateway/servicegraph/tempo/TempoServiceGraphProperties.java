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

package ch.nexsol.gateway.servicegraph.tempo;

import java.time.Duration;

import org.springframework.util.unit.DataSize;

/**
 * Configuration properties for the Tempo service graph source, bound under
 * {@code spring.cloud.gateway.server.webflux.service-graph.tempo}.
 */
public class TempoServiceGraphProperties {

	/**
	 * Base URL of the Prometheus-compatible server the Tempo metrics-generator writes to,
	 * for example {@code http://mimir:9009/prometheus}.
	 * <p>
	 * This is <strong>not</strong> the URL of Tempo. Tempo does not serve a service
	 * graph: its metrics-generator derives one from the spans and writes it as Prometheus
	 * series, and this is where those series are read from.
	 */
	private String url;

	/**
	 * Series counting the calls between two services.
	 */
	private String requestMetric = "traces_service_graph_request_total";

	/**
	 * Series counting the calls between two services that failed.
	 */
	private String failedMetric = "traces_service_graph_request_failed_total";

	/**
	 * Label naming the calling service.
	 */
	private String clientLabel = "client";

	/**
	 * Label naming the called service.
	 */
	private String serverLabel = "server";

	/**
	 * Extra label matchers restricting the series, without the enclosing braces, for
	 * example {@code namespace="prod"}.
	 * <p>
	 * A metrics-generator shared by several environments otherwise draws them as one
	 * graph, joining services that never called each other.
	 */
	private String selector = "";

	/**
	 * How long to wait for the server before reporting no graph.
	 */
	private Duration timeout = Duration.ofSeconds(5);

	/**
	 * Largest answer read from Tempo. It is deserialized whole, so it is buffered whole,
	 * and this is the ceiling the reactive codecs enforce while it is read. When unset
	 * the client keeps the one the application configured through
	 * {@code spring.http.codecs.max-in-memory-size}, 256&nbsp;KB by default.
	 */
	private DataSize maxResponseSize;

	/**
	 * User name of the Basic credentials sent to the server. Set it together with the
	 * password; leave both unset when the server needs no Basic authentication.
	 */
	private String username;

	/**
	 * Password of the Basic credentials sent to the server.
	 */
	private String password;

	/**
	 * Bearer token sent to the server, for one that authenticates with one. Ignored when
	 * Basic credentials are set. Read once, at startup: a token that rotates needs the
	 * application to declare its own {@code tempoServiceGraphWebClient} bean instead.
	 */
	private String token;

	/**
	 * @return the base URL of the server holding the series
	 */
	public String getUrl() {
		return this.url;
	}

	/**
	 * @param url the base URL of the server holding the series
	 */
	public void setUrl(String url) {
		this.url = url;
	}

	/**
	 * @return the request series name
	 */
	public String getRequestMetric() {
		return this.requestMetric;
	}

	/**
	 * @param requestMetric the request series name
	 */
	public void setRequestMetric(String requestMetric) {
		this.requestMetric = requestMetric;
	}

	/**
	 * @return the failed request series name
	 */
	public String getFailedMetric() {
		return this.failedMetric;
	}

	/**
	 * @param failedMetric the failed request series name
	 */
	public void setFailedMetric(String failedMetric) {
		this.failedMetric = failedMetric;
	}

	/**
	 * @return the label naming the calling service
	 */
	public String getClientLabel() {
		return this.clientLabel;
	}

	/**
	 * @param clientLabel the label naming the calling service
	 */
	public void setClientLabel(String clientLabel) {
		this.clientLabel = clientLabel;
	}

	/**
	 * @return the label naming the called service
	 */
	public String getServerLabel() {
		return this.serverLabel;
	}

	/**
	 * @param serverLabel the label naming the called service
	 */
	public void setServerLabel(String serverLabel) {
		this.serverLabel = serverLabel;
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
	 * @return the largest answer read from Tempo
	 */
	public DataSize getMaxResponseSize() {
		return this.maxResponseSize;
	}

	/**
	 * @param maxResponseSize the largest answer read from Tempo
	 */
	public void setMaxResponseSize(DataSize maxResponseSize) {
		this.maxResponseSize = maxResponseSize;
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
