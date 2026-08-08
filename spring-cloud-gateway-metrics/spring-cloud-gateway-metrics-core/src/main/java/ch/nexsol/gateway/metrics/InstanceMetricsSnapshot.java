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
 * What an {@link InstanceMetricsSource} returns: one row per instance together with the
 * coverage the rows were collected over.
 * <p>
 * The coverage is carried with the rows for the same reason it is on
 * {@link RouteMetricsSnapshot}: a list of one instance means something different
 * depending on whether the gateway runs alone or the source could only reach itself.
 *
 * @param coverage what the rows cover, ready to be shown to a human
 * @param instances the per-instance figures, ordered by instance id
 */
public record InstanceMetricsSnapshot(String coverage, List<InstanceMetric> instances) {

	public InstanceMetricsSnapshot {
		instances = Collections.unmodifiableList(List.copyOf(instances));
	}

	/**
	 * An empty snapshot, used by a source that has nothing to report.
	 * @param coverage what the rows would have covered
	 * @return the empty snapshot
	 */
	public static InstanceMetricsSnapshot empty(String coverage) {
		return new InstanceMetricsSnapshot(coverage, List.of());
	}

}
