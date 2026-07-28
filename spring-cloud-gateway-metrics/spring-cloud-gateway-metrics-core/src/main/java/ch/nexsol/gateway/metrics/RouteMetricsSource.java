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

import reactor.core.publisher.Mono;

/**
 * Where the per-route request figures are read from.
 * <p>
 * The default implementation reads the meter registry of the running instance, which is
 * all a single-instance gateway needs. Behind a load balancer that answer is incomplete
 * by construction &mdash; each instance only ever counted its own share of the traffic
 * &mdash; so a provider module contributes a source that reaches beyond the local JVM: a
 * metrics backend, a shared store, or the other instances themselves.
 * <p>
 * Implementations return a {@link Mono} because every source but the local one performs
 * I/O. A source that cannot answer reports an empty snapshot rather than failing: the
 * traffic view is a read-only page, and no figure is better than a broken gateway.
 */
@FunctionalInterface
public interface RouteMetricsSource {

	/**
	 * Collect the current per-route figures.
	 * @return a mono emitting the figures and the coverage they were computed over
	 */
	Mono<RouteMetricsSnapshot> collect();

}
