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

package ch.nexsol.gateway.ui.servicegraph;

import ch.nexsol.gateway.servicegraph.GraphNodeKind;
import ch.nexsol.gateway.servicegraph.ServiceGraphSnapshot;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import ch.nexsol.gateway.ui.overview.OverviewContribution;
import ch.nexsol.gateway.ui.overview.OverviewStat;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Contributes the size of the service graph to the home page.
 * <p>
 * One tile and no more: how many services the gateway saw called, and how many calls join
 * them. Which service calls which is the question the graph itself answers, and it needs
 * the drawing to be answered at all.
 */
public class ServiceGraphOverviewContribution implements OverviewContribution {

	private final ObjectProvider<ServiceGraphSource> graphSource;

	/**
	 * Creates the contribution over the (optional) active graph source.
	 * @param graphSource the provider over the source the graph is read from
	 */
	public ServiceGraphOverviewContribution(ObjectProvider<ServiceGraphSource> graphSource) {
		this.graphSource = graphSource;
	}

	@Override
	public Flux<OverviewStat> stats() {
		return Flux.defer(() -> {
			ServiceGraphSource source = this.graphSource.getIfAvailable();
			if (source == null) {
				return Flux.empty();
			}
			return source.collect().map(ServiceGraphOverviewContribution::toStat).flux();
		});
	}

	/**
	 * Folds the graph into the one figure the home page shows. The detail carries the
	 * edges and the coverage together: a count of services that only covers one instance
	 * is not the same number as one that covers the cluster, and the tile has no room to
	 * say it twice.
	 */
	private static OverviewStat toStat(ServiceGraphSnapshot snapshot) {
		long services = snapshot.nodes().stream().filter((node) -> node.kind() == GraphNodeKind.SERVICE).count();
		int edges = snapshot.edges().size();
		String detail = edges + ((edges == 1) ? " call drawn" : " calls drawn") + " — " + snapshot.coverage();
		return new OverviewStat("Services called", String.valueOf(services), detail, 46);
	}

}
