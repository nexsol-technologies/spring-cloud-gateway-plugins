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

package ch.nexsol.gateway.ui.metrics;

import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.ui.overview.OverviewContribution;
import ch.nexsol.gateway.ui.overview.OverviewStat;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Contributes the instance count to the home page.
 * <p>
 * One tile and no more: the heap, the processor and the pool saturation all need the
 * context the instances view gives them &mdash; which instance, against which ceiling
 * &mdash; and the home page is an overview, not a second dashboard.
 */
public class InstancesOverviewContribution implements OverviewContribution {

	private final ObjectProvider<InstanceMetricsSource> instanceMetricsSource;

	/**
	 * Creates the contribution over the (optional) active instance metrics source.
	 * @param instanceMetricsSource the provider over the source the per-instance figures
	 * are read from
	 */
	public InstancesOverviewContribution(ObjectProvider<InstanceMetricsSource> instanceMetricsSource) {
		this.instanceMetricsSource = instanceMetricsSource;
	}

	@Override
	public Flux<OverviewStat> stats() {
		return Flux.defer(() -> {
			InstanceMetricsSource source = this.instanceMetricsSource.getIfAvailable();
			if (source == null) {
				return Flux.empty();
			}
			return source.collect()
				.map((snapshot) -> new OverviewStat("Instances", String.valueOf(snapshot.instances().size()),
						snapshot.coverage(), 45))
				.flux();
		});
	}

}
