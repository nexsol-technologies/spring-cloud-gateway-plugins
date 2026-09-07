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

package ch.nexsol.gateway.ui.insights;

import java.util.List;
import java.util.stream.Stream;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.ui.insights.ActuatorInstances.Instance;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ActuatorInstances}.
 */
class ActuatorInstancesTests {

	private static final InstanceIdentity IDENTITY = new InstanceIdentity("gateway-1");

	@Test
	void reportsThisInstanceAloneWhenNoMetricsPluginIsPresent() {
		ActuatorInstances directory = new ActuatorInstances(provider(null), IDENTITY);

		List<Instance> instances = directory.list().block();

		assertThat(instances).extracting(Instance::id).containsExactly("gateway-1");
		assertThat(instances.get(0).self()).isTrue();
	}

	@Test
	void listsTheOtherInstancesAfterThisOne() {
		ActuatorInstances directory = new ActuatorInstances(
				provider(source(metric("gateway-1", "http://one:8080"), metric("gateway-2", "http://two:8080"))),
				IDENTITY);

		List<Instance> instances = directory.list().block();

		// This instance is reported once, from its own identity rather than from the
		// figures: it is read on the address the request arrived on, not on the one a
		// provider guessed for it.
		assertThat(instances).extracting(Instance::id).containsExactly("gateway-1", "gateway-2");
		assertThat(instances.get(1).uri()).isEqualTo("http://two:8080");
	}

	/**
	 * An instance no source could put an address on cannot be reached over HTTP whatever
	 * its id says, so offering it would only ever produce a failing view.
	 */
	@Test
	void leavesOutAnInstanceNoSourceKnowsTheAddressOf() {
		ActuatorInstances directory = new ActuatorInstances(
				provider(source(metric("gateway-2", null), metric("gateway-3", "  "))), IDENTITY);

		assertThat(directory.list().block()).extracting(Instance::id).containsExactly("gateway-1");
	}

	@Test
	void resolvesAKnownInstanceToTheAddressItWasReportedAt() {
		ActuatorInstances directory = new ActuatorInstances(provider(source(metric("gateway-2", "http://two:8080"))),
				IDENTITY);

		Instance resolved = directory.resolve("gateway-2").block();

		assertThat(resolved.uri()).isEqualTo("http://two:8080");
		assertThat(resolved.self()).isFalse();
	}

	/**
	 * The directory is the allow-list: a view names an instance by id and never by
	 * address, so an id it does not know has to fall back to this instance rather than be
	 * turned into an address of the caller's choosing.
	 */
	@Test
	void resolvesAnUnknownInstanceToThisOne() {
		ActuatorInstances directory = new ActuatorInstances(provider(source(metric("gateway-2", "http://two:8080"))),
				IDENTITY);

		assertThat(directory.resolve("http://elsewhere.invalid").block().self()).isTrue();
		assertThat(directory.resolve("gateway-9").block().self()).isTrue();
		assertThat(directory.resolve(null).block().self()).isTrue();
	}

	@Test
	void reportsThisInstanceAloneWhenTheSourceFails() {
		InstanceMetricsSource failing = () -> Mono.error(new IllegalStateException("Redis is down"));

		ActuatorInstances directory = new ActuatorInstances(provider(failing), IDENTITY);

		assertThat(directory.list().block()).extracting(Instance::id).containsExactly("gateway-1");
	}

	private static InstanceMetricsSource source(InstanceMetric... metrics) {
		return () -> Mono.just(new InstanceMetricsSnapshot("test", List.of(metrics)));
	}

	private static InstanceMetric metric(String id, String uri) {
		return new InstanceMetric(id, uri, 0, null, null, null, List.of(), null);
	}

	private static ObjectProvider<InstanceMetricsSource> provider(InstanceMetricsSource source) {
		return new ObjectProvider<>() {
			@Override
			public InstanceMetricsSource getObject() {
				throw new UnsupportedOperationException();
			}

			@Override
			public InstanceMetricsSource getObject(Object... args) {
				throw new UnsupportedOperationException();
			}

			@Override
			public InstanceMetricsSource getIfAvailable() {
				return source;
			}

			@Override
			public InstanceMetricsSource getIfUnique() {
				return source;
			}

			@Override
			public Stream<InstanceMetricsSource> stream() {
				return (source != null) ? Stream.of(source) : Stream.empty();
			}
		};
	}

}
