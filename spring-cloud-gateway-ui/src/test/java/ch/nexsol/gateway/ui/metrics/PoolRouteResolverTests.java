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

import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.ui.routes.RouteInventoryService;
import ch.nexsol.gateway.ui.routes.RouteView;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PoolRouteResolver}.
 */
class PoolRouteResolverTests {

	private static final String CONTAINER = "323d64b065a5:8080";

	@SuppressWarnings("unchecked")
	private PoolRouteResolver resolverFor(List<RouteView> routes, ReactiveDiscoveryClient discoveryClient) {
		RouteInventoryService inventory = mock(RouteInventoryService.class);
		when(inventory.routes()).thenReturn(Mono.just(routes));
		ObjectProvider<ReactiveDiscoveryClient> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(discoveryClient);
		return new PoolRouteResolver(inventory, provider);
	}

	private static RouteView route(String routeId, String uri) {
		return new RouteView(routeId, uri, 0, List.of(), List.of(), "Properties", false);
	}

	private static ReactiveDiscoveryClient registry(String serviceId, String host, int port) {
		ReactiveDiscoveryClient client = mock(ReactiveDiscoveryClient.class);
		ServiceInstance instance = new DefaultServiceInstance(serviceId + "-1", serviceId, host, port, false);
		when(client.getServices()).thenReturn(Flux.just(serviceId));
		when(client.getInstances(serviceId)).thenReturn(Flux.just(instance));
		return client;
	}

	@Test
	void namesALoadBalancedRouteThroughTheRegistry() {
		PoolRouteResolver resolver = resolverFor(List.of(route("service-a-route", "lb://SERVICE-A")),
				registry("SERVICE-A", "323d64b065a5", 8080));

		Map<String, List<String>> routes = resolver.routesByAddress(List.of(CONTAINER)).block();

		assertThat(routes).containsExactly(Map.entry(CONTAINER, List.of("service-a-route")));
	}

	@Test
	void namesALiteralRouteWithoutTheRegistry() {
		PoolRouteResolver resolver = resolverFor(List.of(route("legacy", "http://legacy-host:9000")), null);

		Map<String, List<String>> routes = resolver.routesByAddress(List.of("legacy-host:9000")).block();

		assertThat(routes).containsExactly(Map.entry("legacy-host:9000", List.of("legacy")));
	}

	@Test
	void fillsInTheDefaultPortALiteralRouteLeavesOut() {
		PoolRouteResolver resolver = resolverFor(List.of(route("dpi", "https://dpi.example.org")), null);

		// The route declares no port, the connection pool always reports one.
		Map<String, List<String>> routes = resolver.routesByAddress(List.of("dpi.example.org:443")).block();

		assertThat(routes).containsExactly(Map.entry("dpi.example.org:443", List.of("dpi")));
	}

	@Test
	void listsEveryRouteTargetingTheSameDownstream() {
		PoolRouteResolver resolver = resolverFor(
				List.of(route("silex", "lb://SILEX"), route("silex-admin", "lb://silex")),
				registry("SILEX", "323d64b065a5", 8080));

		// A pool serves the service, not the route: hiding one of them would be a lie.
		assertThat(resolver.routesByAddress(List.of(CONTAINER)).block())
			.containsExactly(Map.entry(CONTAINER, List.of("silex", "silex-admin")));
	}

	@Test
	void fallsBackToTheServiceIdWhenNoRoutePointsAtIt() {
		PoolRouteResolver resolver = resolverFor(List.of(), registry("SERVICE-A", "323d64b065a5", 8080));

		assertThat(resolver.routesByAddress(List.of(CONTAINER)).block())
			.containsExactly(Map.entry(CONTAINER, List.of("SERVICE-A")));
	}

	@Test
	void leavesOutAnAddressNeitherTheRoutesNorTheRegistryKnow() {
		PoolRouteResolver resolver = resolverFor(List.of(route("service-a-route", "lb://SERVICE-A")),
				registry("SERVICE-A", "another-host", 8080));

		// Named as nothing rather than as the wrong route: the view shows a dash.
		assertThat(resolver.routesByAddress(List.of(CONTAINER)).block()).isEmpty();
	}

	@Test
	void reportsNoNameRatherThanFailingWhenTheRegistryIsUnreachable() {
		ReactiveDiscoveryClient client = mock(ReactiveDiscoveryClient.class);
		when(client.getServices()).thenReturn(Flux.error(new IllegalStateException("registry down")));
		PoolRouteResolver resolver = resolverFor(List.of(route("service-a-route", "lb://SERVICE-A")), client);

		assertThat(resolver.routesByAddress(List.of(CONTAINER)).block()).isEmpty();
	}

	@Test
	void doesNotReadTheRegistryWhenEveryAddressIsAlreadyNamed() {
		ReactiveDiscoveryClient client = mock(ReactiveDiscoveryClient.class);
		PoolRouteResolver resolver = resolverFor(List.of(route("legacy", "http://legacy-host:9000")), client);

		assertThat(resolver.routesByAddress(List.of("legacy-host:9000")).block()).hasSize(1);
		// A strict expectation: the registry is read only for what the route table could
		// not name, since the view polls and a registry read is not free.
		verifyNoInteractions(client);
	}

	@Test
	void reportsNoRouteWhenNoPoolWasReported() {
		PoolRouteResolver resolver = resolverFor(List.of(route("service-a-route", "lb://SERVICE-A")),
				registry("SERVICE-A", "323d64b065a5", 8080));

		assertThat(resolver.routesByAddress(List.of()).block()).isEmpty();
	}

}
