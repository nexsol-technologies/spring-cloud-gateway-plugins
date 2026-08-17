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

import java.util.ArrayList;
import java.util.List;

import ch.nexsol.gateway.servicegraph.GraphEdge;
import ch.nexsol.gateway.servicegraph.ServiceGraphFilter;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import ch.nexsol.gateway.servicegraph.prometheus.PrometheusQueryResponse.Sample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Reads the graph this gateway publishes back from Prometheus, so the view shows the
 * whole gateway rather than the instance that answered.
 * <p>
 * This is the only source that survives an instance being replaced: the series outlive
 * the JVM that produced them, so a rolling restart does not reset the edges the way a
 * meter registry does.
 * <p>
 * One instant query is enough: the counter carries the caller, the service, the route and
 * the outcome as labels, so summing by all four returns every edge, split by outcome, in
 * a single vector.
 */
public class PrometheusServiceGraphSource implements ServiceGraphSource {

	private static final Logger LOG = LoggerFactory.getLogger(PrometheusServiceGraphSource.class);

	private final PrometheusQuery query;

	private final PrometheusServiceGraphProperties properties;

	private final String coverage;

	/**
	 * Creates the source querying the configured Prometheus.
	 * @param webClient the client used to query Prometheus
	 * @param properties the Prometheus configuration
	 */
	public PrometheusServiceGraphSource(WebClient webClient, PrometheusServiceGraphProperties properties) {
		this.query = new PrometheusQuery(webClient, properties.getTimeout());
		this.properties = properties;
		this.coverage = "every instance, from Prometheus";
	}

	@Override
	public Mono<ServiceGraphSnapshot> collect() {
		String expression = "sum by (" + ServiceGraphFilter.CALLER_TAG + ", " + ServiceGraphFilter.SERVICE_TAG + ", "
				+ ServiceGraphFilter.ROUTE_TAG + ", " + ServiceGraphFilter.OUTCOME_TAG + ") ("
				+ PrometheusQuery.series(this.properties.getMeter(), this.properties.getSelector()) + ")";
		return this.query.run(expression)
			.map((samples) -> ServiceGraphSnapshot.of(this.coverage, toEdges(samples)))
			.onErrorResume((ex) -> {
				LOG.warn("Could not read the service graph from Prometheus at {}: {}", this.properties.getUrl(),
						ex.getMessage());
				return Mono.just(ServiceGraphSnapshot.empty(this.coverage + " — " + PrometheusQuery.reason(ex)));
			});
	}

	/**
	 * Turns each sample into a partial edge. The outcome stays out of the edge and is
	 * only read to tell the failures apart, so the samples of one pair are summed back
	 * together by the snapshot.
	 */
	private static List<GraphEdge> toEdges(List<Sample> samples) {
		List<GraphEdge> edges = new ArrayList<>();
		for (Sample sample : samples) {
			String caller = sample.label(ServiceGraphFilter.CALLER_TAG);
			String service = sample.label(ServiceGraphFilter.SERVICE_TAG);
			if (caller == null || service == null) {
				continue;
			}
			long calls = Math.round(sample.doubleValue());
			boolean failed = ServiceGraphFilter.SERVER_ERROR.equals(sample.label(ServiceGraphFilter.OUTCOME_TAG));
			edges.add(new GraphEdge(caller, service, sample.label(ServiceGraphFilter.ROUTE_TAG), calls,
					failed ? calls : 0));
		}
		return edges;
	}

}
