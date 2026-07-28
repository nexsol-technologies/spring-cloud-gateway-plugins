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

package ch.nexsol.gateway.openapi.hub.discovery;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import ch.nexsol.gateway.openapi.HubOpenapiProperties;
import ch.nexsol.gateway.openapi.hub.OpenapiService;
import ch.nexsol.gateway.openapi.hub.OpenapiService.OpenapiDiscover;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link HubDiscoveryRouteLocator}.
 */
class HubDiscoveryRouteLocatorTests {

	private final ReactiveDiscoveryClient discoveryClient = mock(ReactiveDiscoveryClient.class);

	private final OpenapiService openapiService = mock(OpenapiService.class);

	@Test
	void probesNoMoreServicesAtOnceThanConfigured() {
		List<String> services = IntStream.rangeClosed(1, 60).mapToObj((i) -> "service-" + i).toList();
		given(this.discoveryClient.getServices()).willReturn(Flux.fromIterable(services));
		given(this.discoveryClient.getInstances(anyString())).willAnswer((invocation) -> {
			String serviceId = invocation.getArgument(0);
			return Flux
				.just(new DefaultServiceInstance(serviceId + "-1", serviceId, serviceId + ".example", 8080, false));
		});

		AtomicInteger inFlight = new AtomicInteger();
		AtomicInteger highWaterMark = new AtomicInteger();
		// doOnTerminate, not doFinally: the count has to drop before flatMap sees the
		// completion, or the next probe counts as overlapping the one that just ended.
		given(this.openapiService.discoverOpenapiUrl(anyString(), any())).willAnswer((invocation) -> Mono.defer(() -> {
			highWaterMark.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
			return Mono.delay(Duration.ofMillis(20)).then(Mono.<OpenapiDiscover>empty());
		}).doOnTerminate(inFlight::decrementAndGet));

		HubOpenapiProperties.Discovery properties = new HubOpenapiProperties.Discovery();
		properties.setConcurrency(4);
		HubDiscoveryRouteLocator locator = new HubDiscoveryRouteLocator(this.discoveryClient,
				new DiscoveryLocatorProperties(), this.openapiService, properties);

		StepVerifier.create(locator.getRouteDefinitions()).verifyComplete();

		// Without the bound, flatMap would probe all 60 services at once -- and 150 of
		// them just as happily, which is what saturates the connection pool.
		assertThat(highWaterMark).hasValueBetween(2, 4);
	}

}
