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

package ch.nexsol.gateway.openapi.hub;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.nexsol.gateway.openapi.HubOpenapiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests what the hub does of a service out of reach when nothing was configured: it is
 * left alone for a while, rather than costing every refresh of the routes a full timeout
 * for as long as it stays down.
 */
class OpenapiDiscoveryDefaultsTests {

	private final ReactiveDiscoveryClient discoveryClient = mock(ReactiveDiscoveryClient.class);

	private final List<String> probedUrls = new CopyOnWriteArrayList<>();

	private final WebClient webClient = WebClient.builder().exchangeFunction((request) -> {
		this.probedUrls.add(request.url().toString());
		return Mono.error(new IllegalStateException("connection refused"));
	}).build();

	private final HubOpenapiProperties.Discovery defaults = new HubOpenapiProperties.Discovery();

	@BeforeEach
	void givenInstance() {
		when(this.discoveryClient.getInstances("service-a")).thenReturn(Flux
			.just(new DefaultServiceInstance("service-a-1", "service-a", "service-a.example", 8080, false, Map.of())));
	}

	@Test
	void leavesAnUnreachableServiceAloneByDefault() {
		OpenapiService service = new OpenapiService(this.discoveryClient, this.webClient, this.defaults.getCacheTtl(),
				this.defaults.getFailureCacheTtl());

		StepVerifier.create(service.discoverOpenapiUrl("service-a", new RouteDefinition())).verifyComplete();
		StepVerifier.create(service.discoverOpenapiUrl("service-a", new RouteDefinition())).verifyComplete();

		assertThat(this.probedUrls).hasSize(1);
	}

	@Test
	void leavesItAloneForLessTimeThanAServiceWithoutDocument() {
		assertThat(this.defaults.getFailureCacheTtl()).isPositive().isLessThan(this.defaults.getCacheTtl());
		assertThat(this.defaults.getFailureCacheTtl()).isEqualTo(Duration.ofSeconds(30));
	}

}
