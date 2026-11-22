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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.ui.insights.ActuatorInstances.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that listing the instances does not read the figures of the whole fleet each time
 * it is asked. The list is only ids and addresses, but the source it comes from collects
 * every figure of every instance, and one introspection view asks for it twice: once to
 * draw its picker, once to read its data.
 */
class ActuatorInstancesCachingTests {

	private final AtomicInteger collected = new AtomicInteger();

	private VirtualTimeScheduler clock;

	private ActuatorInstances directory;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.clock = VirtualTimeScheduler.getOrSet();
		InstanceMetricsSource source = () -> Mono.fromSupplier(() -> {
			int reading = this.collected.incrementAndGet();
			return new InstanceMetricsSnapshot("test", List.of(new InstanceMetric("gateway-" + (reading + 1),
					"http://other:8080", 0, null, null, null, List.of(), null)));
		});
		ObjectProvider<InstanceMetricsSource> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(source);
		this.directory = new ActuatorInstances(provider, new InstanceIdentity("gateway-1"));
	}

	@AfterEach
	void tearDown() {
		VirtualTimeScheduler.reset();
	}

	@Test
	void readsTheFleetOnceForTheListAndTheResolutionThatFollowIt() {
		List<Instance> listed = this.directory.list().block();
		Instance resolved = this.directory.resolve("gateway-2").block();
		this.directory.list().block();

		assertThat(listed).extracting(Instance::id).containsExactly("gateway-1", "gateway-2");
		assertThat(resolved.uri()).isEqualTo("http://other:8080");
		assertThat(this.collected).hasValue(1);
	}

	@Test
	void readsTheFleetAgainOnceTheListHasAged() {
		this.directory.list().block();
		this.clock.advanceTimeBy(Duration.ofSeconds(6));

		assertThat(this.directory.list().block()).extracting(Instance::id).containsExactly("gateway-1", "gateway-3");
		assertThat(this.collected).hasValue(2);
	}

}
