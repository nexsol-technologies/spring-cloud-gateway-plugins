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

package ch.nexsol.gateway.ui.metrics;

import java.time.Duration;
import java.util.List;

import ch.nexsol.gateway.ui.routes.RouteInventoryService;
import ch.nexsol.gateway.ui.routes.RouteView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that naming the connection pools does not enumerate the service registry on every
 * poll of the runtime view. A pool towards a load-balanced route is reported under the
 * address of an instance, which only the registry can name &mdash; so the registry is
 * needed on every poll, by every open tab, and a registry answering over the network is
 * then asked for every service every five seconds.
 */
class PoolRouteResolverRegistryCacheTests {

	private static final String CONTAINER = "323d64b065a5:8080";

	private VirtualTimeScheduler clock;

	private ReactiveDiscoveryClient registry;

	private PoolRouteResolver resolver;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		this.clock = VirtualTimeScheduler.getOrSet();
		this.registry = mock(ReactiveDiscoveryClient.class);
		when(this.registry.getServices()).thenReturn(Flux.just("SERVICE-A"));
		when(this.registry.getInstances("SERVICE-A"))
			.thenReturn(Flux.just(new DefaultServiceInstance("SERVICE-A-1", "SERVICE-A", "323d64b065a5", 8080, false)));
		RouteInventoryService inventory = mock(RouteInventoryService.class);
		when(inventory.routes()).thenReturn(Mono.just(List
			.of(new RouteView("service-a-route", "lb://SERVICE-A", 0, List.of(), List.of(), "properties", false))));
		ObjectProvider<ReactiveDiscoveryClient> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(this.registry);
		this.resolver = new PoolRouteResolver(inventory, provider);
	}

	@AfterEach
	void tearDown() {
		VirtualTimeScheduler.reset();
	}

	@Test
	void readsTheRegistryOnceForThePollsThatFollowEachOther() {
		for (int poll = 0; poll < 3; poll++) {
			assertThat(this.resolver.routesByAddress(List.of(CONTAINER)).block()).containsEntry(CONTAINER,
					List.of("service-a-route"));
		}

		verify(this.registry, times(1)).getInstances("SERVICE-A");
	}

	@Test
	void readsTheRegistryAgainOnceWhatItSaidHasAged() {
		this.resolver.routesByAddress(List.of(CONTAINER)).block();
		this.clock.advanceTimeBy(Duration.ofSeconds(31));
		this.resolver.routesByAddress(List.of(CONTAINER)).block();

		verify(this.registry, times(2)).getInstances("SERVICE-A");
	}

	@Test
	void doesNotRememberARegistryThatFailedToAnswer() {
		when(this.registry.getInstances("SERVICE-A")).thenReturn(Flux.error(new IllegalStateException("down")),
				Flux.just(new DefaultServiceInstance("SERVICE-A-1", "SERVICE-A", "323d64b065a5", 8080, false)));

		assertThat(this.resolver.routesByAddress(List.of(CONTAINER)).block()).isEmpty();
		assertThat(this.resolver.routesByAddress(List.of(CONTAINER)).block()).containsEntry(CONTAINER,
				List.of("service-a-route"));
	}

}
