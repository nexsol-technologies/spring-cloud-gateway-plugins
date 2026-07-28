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

import java.util.Collections;
import java.util.List;

/**
 * What a {@link RouteMetricsSource} returns: the per-route figures together with the
 * coverage they were computed over.
 * <p>
 * The coverage is carried with the figures rather than inferred by the view, because it
 * is the only thing telling a reader whether a number is the whole gateway or one
 * instance of it. A view that hides it shows a count that silently means something
 * different depending on which instance answered.
 *
 * @param coverage what the figures cover, ready to be shown to a human &mdash; the local
 * instance id, the metrics backend, the number of instances reached
 * @param metrics the per-route figures, ordered by request count descending
 */
public record RouteMetricsSnapshot(String coverage, List<RouteMetric> metrics) {

	public RouteMetricsSnapshot {
		metrics = Collections.unmodifiableList(List.copyOf(metrics));
	}

	/**
	 * An empty snapshot, used by a source that has nothing to report.
	 * @param coverage what the figures would have covered
	 * @return the empty snapshot
	 */
	public static RouteMetricsSnapshot empty(String coverage) {
		return new RouteMetricsSnapshot(coverage, List.of());
	}

}
