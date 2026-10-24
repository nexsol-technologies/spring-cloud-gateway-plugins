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

package ch.nexsol.gateway.mcp.server.metrics;

import java.util.List;

import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.metrics.RouteMetric;
import ch.nexsol.gateway.metrics.RouteMetricsSnapshot;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsToolsTests {

	private final RouteMetricsSnapshot traffic = new RouteMetricsSnapshot("this instance only (gw-1)",
			List.of(new RouteMetric("books", "http://books", 10, 12.5, 40.0, 1, 0.1, 0, 0.0)));

	@Test
	void servesTheTrafficSnapshotOfTheSourceAsIs() {
		MetricsTools tools = new MetricsTools(() -> Mono.just(this.traffic), instances());
		StepVerifier.create(tools.routeTraffic()).expectNext(this.traffic).verifyComplete();
	}

	@Test
	void servesTheInstancesOfTheSourceWhenThereIsOne() {
		InstanceMetricsSnapshot instances = new InstanceMetricsSnapshot("2 instances, via Redis", List.of());
		MetricsTools tools = new MetricsTools(() -> Mono.just(this.traffic), instances(() -> Mono.just(instances)));
		StepVerifier.create(tools.gatewayInstances()).expectNext(instances).verifyComplete();
	}

	@Test
	void saysSoWhenNoInstanceSourceIsWired() {
		MetricsTools tools = new MetricsTools(() -> Mono.just(this.traffic), instances());
		StepVerifier.create(tools.gatewayInstances()).assertNext((snapshot) -> {
			assertThat(snapshot.coverage()).isEqualTo(MetricsTools.NO_INSTANCE_SOURCE);
			assertThat(snapshot.instances()).isEmpty();
		}).verifyComplete();
	}

	private static ObjectProvider<InstanceMetricsSource> instances(InstanceMetricsSource... sources) {
		StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
		for (int i = 0; i < sources.length; i++) {
			beanFactory.addBean("instances" + i, sources[i]);
		}
		return beanFactory.getBeanProvider(InstanceMetricsSource.class);
	}

}
