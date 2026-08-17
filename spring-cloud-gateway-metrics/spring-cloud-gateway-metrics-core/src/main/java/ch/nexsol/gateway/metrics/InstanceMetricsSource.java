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

import ch.nexsol.gateway.commons.InstanceIdentity;
import reactor.core.publisher.Mono;

/**
 * Where the per-instance technical figures are read from.
 * <p>
 * The default implementation reads the meter registry of the running instance, which
 * reports a single row. A provider module replaces it with a source listing every
 * instance of the gateway: the other instances themselves, a shared store, or a metrics
 * backend.
 * <p>
 * This is deliberately a separate contract from {@link RouteMetricsSource} rather than a
 * specialisation of it. Route figures are <em>merged</em> across instances &mdash; three
 * instances serving one route produce one number &mdash; while instance figures never
 * are: each instance is a row of its own, and the average heap of a cluster means
 * nothing. The two share the reading of {@link InstanceIdentity} and the vocabulary of
 * the coverage, which is all they have in common.
 * <p>
 * Implementations return a {@link Mono} because every source but the local one performs
 * I/O. A source that cannot answer reports an empty snapshot rather than failing.
 */
@FunctionalInterface
public interface InstanceMetricsSource {

	/**
	 * Collect the current per-instance figures.
	 * @return a mono emitting the figures and the coverage they were computed over
	 */
	Mono<InstanceMetricsSnapshot> collect();

}
