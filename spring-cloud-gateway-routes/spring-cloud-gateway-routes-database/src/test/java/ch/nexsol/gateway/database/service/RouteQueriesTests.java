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

package ch.nexsol.gateway.database.service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import ch.nexsol.gateway.database.entity.FilterEntity;
import ch.nexsol.gateway.database.entity.PredicateEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.locator.DatabaseRouteDefinitionLocator;
import ch.nexsol.gateway.database.model.RouteResponseModel;
import ch.nexsol.gateway.database.repository.FilterRepository;
import ch.nexsol.gateway.database.repository.PredicateRepository;
import ch.nexsol.gateway.database.repository.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.reactive.TransactionalOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests how many statements reading the routes costs. Every route has its predicates and
 * its filters in two other tables: read per route, a table of N routes costs 1 + 2N
 * statements, all in flight at once against a pool of a few connections.
 */
class RouteQueriesTests {

	private final RouteRepository routeRepository = mock(RouteRepository.class);

	private final PredicateRepository predicateRepository = mock(PredicateRepository.class);

	private final FilterRepository filterRepository = mock(FilterRepository.class);

	private final ArgumentService argumentService = new ArgumentService(new ObjectMapper());

	private final GatewayConfigService configService = new GatewayConfigService(List.of(), List.of());

	private final PredicateService predicateService = new PredicateService(this.configService, this.predicateRepository,
			this.argumentService);

	private final FilterService filterService = new FilterService(this.configService, this.filterRepository,
			this.argumentService);

	private final RouteService routeService = new RouteService(this.routeRepository, this.predicateService,
			this.filterService, mock(ApplicationEventPublisher.class), mock(TransactionalOperator.class));

	@BeforeEach
	void threeRoutesWithTheirElements() {
		when(this.routeRepository.findAll())
			.thenReturn(Flux.just(route(1, "orders"), route(2, "billing"), route(3, "users")));
		List<PredicateEntity> predicates = List.of(predicate(10, 1, "/orders/**"), predicate(11, 2, "/billing/**"),
				predicate(12, 3, "/users/**"));
		List<FilterEntity> filters = List.of(filter(20, 2));
		when(this.predicateRepository.findAll()).thenReturn(Flux.fromIterable(predicates));
		when(this.filterRepository.findAll()).thenReturn(Flux.fromIterable(filters));
		when(this.predicateRepository.findByRouteRefId(anyLong())).thenAnswer((call) -> Flux
			.fromIterable(predicates.stream().filter((it) -> it.getRouteRefId().equals(call.getArgument(0))).toList()));
		when(this.filterRepository.findByRouteRefId(anyLong())).thenAnswer((call) -> Flux
			.fromIterable(filters.stream().filter((it) -> it.getRouteRefId().equals(call.getArgument(0))).toList()));
		when(this.predicateRepository.findByRouteRefIdIn(anyCollection())).thenReturn(Flux.fromIterable(predicates));
		when(this.filterRepository.findByRouteRefIdIn(anyCollection())).thenReturn(Flux.fromIterable(filters));
	}

	@Test
	void listingTheRoutesReadsEachTableOnce() {
		ApiService apiService = new ApiService(this.routeService, this.predicateService, this.filterService,
				this.argumentService);

		List<RouteResponseModel> routes = apiService.getAllRoutes().collectList().block();

		assertThat(routes).extracting(RouteResponseModel::routeId).containsExactly("orders", "billing", "users");
		assertThat(routes.get(0).predicates()).singleElement()
			.satisfies((predicate) -> assertThat(predicate.args()).containsEntry("patterns", "/orders/**"));
		assertThat(routes.get(0).filters()).isEmpty();
		assertThat(routes.get(1).filters()).singleElement()
			.satisfies((filter) -> assertThat(filter.name()).isEqualTo("StripPrefix"));
		verify(this.predicateRepository, never()).findByRouteRefId(anyLong());
		verify(this.filterRepository, never()).findByRouteRefId(anyLong());
		verify(this.predicateRepository, times(1)).findAll();
		verify(this.filterRepository, times(1)).findAll();
	}

	@Test
	void readingOneRouteStillReadsItsElementsAlone() {
		ApiService apiService = new ApiService(this.routeService, this.predicateService, this.filterService,
				this.argumentService);
		when(this.routeRepository.findById(2L)).thenReturn(Mono.just(route(2, "billing")));

		RouteResponseModel route = apiService.findById(2L).block();

		assertThat(route.predicates()).hasSize(1);
		assertThat(route.filters()).hasSize(1);
		verify(this.predicateRepository, never()).findAll();
	}

	@Test
	void loadingTheRouteTableBindsNoRouteIdIntoItsStatements() {
		List<RouteDefinition> definitions = this.routeService.loadSpringCloudGatewayRouteDefinition()
			.collectList()
			.block();

		assertThat(definitions).extracting(RouteDefinition::getId).containsExactly("orders", "billing", "users");
		assertThat(definitions.get(1).getFilters()).hasSize(1);
		// Every row is wanted: naming each route in an IN list grows the statement with
		// the table, up to the number of parameters the driver accepts.
		verify(this.predicateRepository, never()).findByRouteRefIdIn(any());
		verify(this.filterRepository, never()).findByRouteRefIdIn(any());
		verify(this.predicateRepository, times(1)).findAll();
		verify(this.filterRepository, times(1)).findAll();
	}

	@Test
	void theLocatorGivesUpOnADatabaseThatNeverAnswers() {
		RouteService silent = mock(RouteService.class);
		when(silent.loadSpringCloudGatewayRouteDefinition()).thenReturn(Flux.never());

		StepVerifier.withVirtualTime(() -> new DatabaseRouteDefinitionLocator(silent).getRouteDefinitions())
			.expectSubscription()
			.thenAwait(Duration.ofSeconds(31))
			.expectError(TimeoutException.class)
			.verify(Duration.ofSeconds(3));
	}

	private static RouteEntity route(long id, String routeId) {
		RouteEntity route = new RouteEntity();
		route.setId(id);
		route.setRouteId(routeId);
		route.setUri("http://" + routeId);
		return route;
	}

	private static PredicateEntity predicate(long id, long routeId, String pattern) {
		PredicateEntity predicate = new PredicateEntity();
		predicate.setId(id);
		predicate.setRouteRefId(routeId);
		predicate.setName("Path");
		predicate.setArgs("{\"patterns\":\"" + pattern + "\"}");
		return predicate;
	}

	private static FilterEntity filter(long id, long routeId) {
		FilterEntity filter = new FilterEntity();
		filter.setId(id);
		filter.setRouteRefId(routeId);
		filter.setName("StripPrefix");
		filter.setArgs("{\"parts\":\"1\"}");
		return filter;
	}

}
