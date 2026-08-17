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

package ch.nexsol.gateway.servicegraph;

import reactor.core.publisher.Mono;

/**
 * Where the graph is read from.
 * <p>
 * The default implementation reads the counters of the running instance, which is all a
 * single-instance gateway needs. Behind a load balancer that answer is a sample by
 * construction &mdash; each instance only ever counted the calls it served &mdash; so a
 * provider module contributes a source reaching beyond the local JVM: a shared store, a
 * metrics backend, or a tracing backend that already knows the whole graph.
 * <p>
 * Implementations return a {@link Mono} because every source but the local one performs
 * I/O. A source that cannot answer reports an empty snapshot rather than failing: the
 * graph is a read-only page, and no graph is better than a broken gateway.
 */
@FunctionalInterface
public interface ServiceGraphSource {

	/**
	 * Collect the current graph.
	 * @return a mono emitting the graph and the coverage it was computed over
	 */
	Mono<ServiceGraphSnapshot> collect();

}
