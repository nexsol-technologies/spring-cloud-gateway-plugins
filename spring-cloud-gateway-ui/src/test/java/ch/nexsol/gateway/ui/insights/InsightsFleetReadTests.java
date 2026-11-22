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
import java.util.Map;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.InstanceMetricsSnapshot;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests how long the loggers of a whole fleet take to read. Read one instance after the
 * other, the view waits for the sum of their answers &mdash; and for the sum of their
 * timeouts when some are down.
 */
class InsightsFleetReadTests {

	private final MockServerWebExchange exchange = MockServerWebExchange
		.from(MockServerHttpRequest.get("/ui/insights/loggers/data").build());

	@Test
	void readsTheInstancesOfAFleetTogetherRatherThanOneAfterTheOther() {
		ActuatorClient client = mock(ActuatorClient.class);
		StepVerifier.withVirtualTime(() -> {
			when(client.read(any(), any(), eq("loggers")))
				.thenAnswer((call) -> Mono.delay(Duration.ofSeconds(1)).thenReturn(loggers()));
			return controller(client).data("loggers", ActuatorInstances.ALL, this.exchange);
		})
			.expectSubscription()
			// Four instances answering in one second each: one second together, four in a
			// row.
			.thenAwait(Duration.ofMillis(1500))
			.assertNext((answer) -> assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK))
			.expectComplete()
			.verify(Duration.ofSeconds(3));
	}

	private static Map<String, Object> loggers() {
		return Map.of("levels", List.of("INFO", "DEBUG"), "loggers",
				Map.of("ROOT", Map.of("configuredLevel", "INFO", "effectiveLevel", "INFO")));
	}

	@SuppressWarnings("unchecked")
	private static InsightsController controller(ActuatorClient client) {
		InstanceMetricsSource source = () -> Mono.just(new InstanceMetricsSnapshot("test",
				List.of(metric("gateway-2"), metric("gateway-3"), metric("gateway-4"))));
		ObjectProvider<InstanceMetricsSource> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(source);
		ActuatorInstances instances = new ActuatorInstances(provider, new InstanceIdentity("gateway-1"));
		return new InsightsController(client, instances, mock(LoggerWriteAccess.class), mock(LoggerBaseline.class));
	}

	private static InstanceMetric metric(String id) {
		return new InstanceMetric(id, "http://" + id + ":8080", 0, null, null, null, List.of(), null);
	}

}
