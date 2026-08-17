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

package ch.nexsol.gateway.servicegraph.prometheus;

import java.time.Duration;
import java.util.List;

import ch.nexsol.gateway.servicegraph.prometheus.PrometheusQueryResponse.Sample;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Runs instant queries against a Prometheus-compatible server and returns the vector they
 * answered with.
 * <p>
 * Shared by every source reading series rather than counting them itself: the graph this
 * gateway publishes, and the graph a tracing backend derives from spans, are read the
 * same way and differ only in the series they ask for.
 */
public class PrometheusQuery {

	private final WebClient webClient;

	private final Duration timeout;

	/**
	 * Creates the query runner.
	 * @param webClient the client querying the server, carrying its base URL and
	 * credentials
	 * @param timeout how long to wait for an answer
	 */
	public PrometheusQuery(WebClient webClient, Duration timeout) {
		this.webClient = webClient;
		this.timeout = timeout;
	}

	/**
	 * Runs one instant query.
	 * @param expression the PromQL expression
	 * @return the samples it answered with
	 */
	public Mono<List<Sample>> run(String expression) {
		return this.webClient.get()
			// The expression is passed as a URI variable rather than inlined: a PromQL
			// selector is written between braces, which the URI builder would otherwise
			// read as a template placeholder and fail to expand.
			.uri((builder) -> builder.path("/api/v1/query").queryParam("query", "{query}").build(expression))
			.retrieve()
			.bodyToMono(PrometheusQueryResponse.class)
			.timeout(this.timeout)
			.map((response) -> (response.data() != null && response.data().result() != null) ? response.data().result()
					: List.<Sample>of());
	}

	/**
	 * Names why a reading failed, so the view separates a server that refused the
	 * credentials from one that could not be reached at all. Reported as one and the same
	 * failure, an expired token is indistinguishable from a network outage.
	 * @param ex the failure
	 * @return a short reason, ready to be shown next to the coverage
	 */
	public static String reason(Throwable ex) {
		if (ex instanceof WebClientResponseException response) {
			HttpStatusCode status = response.getStatusCode();
			if (status.value() == HttpStatus.UNAUTHORIZED.value() || status.value() == HttpStatus.FORBIDDEN.value()) {
				return "authentication refused (" + status.value() + ")";
			}
			return "refused with " + status.value();
		}
		return "unreachable";
	}

	/**
	 * Builds a series selector, guarding against a selector property declared with no
	 * value: that binds to {@code null} rather than to the empty default, and
	 * concatenating it would send {@code {null}} to Prometheus. The query would then fail
	 * on every refresh with nothing saying why.
	 * @param metric the metric name
	 * @param selector the configured label matchers, without the braces
	 * @return the selector
	 */
	public static String series(String metric, String selector) {
		return metric + "{" + ((selector != null) ? selector : "") + "}";
	}

}
