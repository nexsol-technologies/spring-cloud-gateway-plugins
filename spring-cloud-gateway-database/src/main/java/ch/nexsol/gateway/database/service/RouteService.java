/*
 * Copyright 2024 the original author or authors.
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

import java.net.URI;
import java.util.List;
import java.util.function.Function;

import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.exception.RouteNotFoundException;
import ch.nexsol.gateway.database.model.RouteCreateModel;
import ch.nexsol.gateway.database.repository.RouteRepository;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class RouteService {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RouteService.class);

	private final RouteRepository routeRepository;

	private final PredicateService predicateService;

	private final FilterService filterService;

	private final ApplicationEventPublisher publisher;

	public RouteService(RouteRepository routeRepository, PredicateService predicateService, FilterService filterService,
			ApplicationEventPublisher publisher) {
		this.routeRepository = routeRepository;
		this.predicateService = predicateService;
		this.filterService = filterService;
		this.publisher = publisher;
	}

	public Flux<RouteEntity> getAllRoutes() {
		return this.routeRepository.findAll();
	}

	public Flux<RouteDefinition> loadSpringCloudGatewayRouteDefinition() {
		return this.routeRepository.findAll().flatMap(routeEntity -> {

			Mono<List<PredicateDefinition>> predicates = this.predicateService
				.loadSpringCloudGatewayPredicateDefinition(routeEntity.getId());

			Mono<List<FilterDefinition>> filters = this.filterService
				.loadSpringCloudGatewayFilterDefinition(routeEntity.getId());

			return Mono.zip(Mono.just(routeEntity), predicates, filters).map(tuple -> {
				RouteEntity route = tuple.getT1();
				List<PredicateDefinition> predicateDefinitions = tuple.getT2();
				List<FilterDefinition> filterDefinitions = tuple.getT3();

				RouteDefinition routeDefinition = new RouteDefinition();
				routeDefinition.setId(route.getRouteId());
				routeDefinition.setUri(URI.create(route.getUri()));
				if (route.getOrder() != null) {
					routeDefinition.setOrder(route.getOrder());
				}
				routeDefinition.setPredicates(predicateDefinitions);
				routeDefinition.setFilters(filterDefinitions);

				return routeDefinition;
			});
		});
	}

	public Mono<RouteEntity> findById(Long id) {
		return this.routeRepository.findById(id);
	}

	public Mono<RouteEntity> save(RouteEntity routeEntity) {
		return this.routeRepository.save(routeEntity);
	}

	public Mono<RouteEntity> createRoute(@Valid RouteCreateModel routeModel) {
		return Mono
			.zip(this.predicateService.validatePredicatesArgs(routeModel.predicates()),
					this.filterService.validateFiltersArgs(routeModel.filters()))
			.flatMap(__ -> {
				RouteEntity routeEntity = new RouteEntity();
				routeEntity.setRouteId(routeModel.routeId());
				routeEntity.setUri(routeModel.uri().toASCIIString());
				routeEntity.setOrder(routeModel.order());
				return this.save(routeEntity).flatMap(createPredicates(routeModel)).flatMap(createFilters(routeModel));
			})
			.doOnNext(routeEntity -> this.publisher.publishEvent(new RefreshRoutesEvent(this)))
			.subscribeOn(Schedulers.boundedElastic());
	}

	public Mono<RouteEntity> updateRoute(Long routeId, @Valid RouteCreateModel routeModel) {
		return Mono
			.zip(this.findById(routeId).switchIfEmpty(Mono.error(new RouteNotFoundException())),
					this.predicateService.validatePredicatesArgs(routeModel.predicates()),
					this.filterService.validateFiltersArgs(routeModel.filters()))
			.flatMap(tuple3 -> {
				RouteEntity routeEntity = tuple3.getT1();
				routeEntity.setRouteId(routeModel.routeId());
				routeEntity.setUri(routeModel.uri().toASCIIString());
				routeEntity.setOrder(routeModel.order());
				return this.save(routeEntity)
					.flatMap(deletePredicates())
					.flatMap(deleteFilters())
					.flatMap(createPredicates(routeModel))
					.flatMap(createFilters(routeModel))
					.doOnNext(r -> this.publisher.publishEvent(new RefreshRoutesEvent(this)));
			})
			.subscribeOn(Schedulers.boundedElastic());
	}

	private Function<RouteEntity, Mono<RouteEntity>> deletePredicates() {
		return r -> this.predicateService.deleteByRouteId(r.getId()).map(__ -> r).switchIfEmpty(Mono.just(r));
	}

	private Function<RouteEntity, Mono<RouteEntity>> deleteFilters() {
		return r -> this.filterService.deleteByRouteId(r.getId()).map(__ -> r).switchIfEmpty(Mono.just(r));
	}

	private Function<RouteEntity, Mono<RouteEntity>> createFilters(RouteCreateModel routeModel) {
		return r -> this.filterService.createFilters(r, routeModel.filters())
			.collectList()
			.map(l -> r)
			.defaultIfEmpty(r);
	}

	private Function<RouteEntity, Mono<RouteEntity>> createPredicates(RouteCreateModel routeModel) {
		return r -> this.predicateService.createPredicates(r, routeModel.predicates())
			.collectList()
			.map(l -> r)
			.defaultIfEmpty(r);
	}

}
