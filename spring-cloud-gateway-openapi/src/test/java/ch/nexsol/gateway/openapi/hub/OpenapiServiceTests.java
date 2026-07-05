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

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinition;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenapiService}.
 */
class OpenapiServiceTests {

	private final ReactiveDiscoveryClient discoveryClient = mock(ReactiveDiscoveryClient.class);

	private final OpenapiService openapiService = new OpenapiService(this.discoveryClient);

	@Test
	void completesEmptyWhenTheRouteHasNoInstance() {
		when(this.discoveryClient.getInstances("service-a")).thenReturn(Flux.empty());

		StepVerifier.create(this.openapiService.discoverOpenapiUrl("service-a", new RouteDefinition()))
			.verifyComplete();
	}

}
