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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.exception.RouteAlreadyExistException;
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
		return this.routeRepository.findAll().collectList().flatMapMany((routes) -> {
			List<Long> routeIds = routes.stream().map(RouteEntity::getId).toList();

			// Batch-load predicates and filters for every route in a single query each
			// (3 queries total) instead of 1 + 2N on the route-resolution hot path.
			Mono<Map<Long, List<PredicateDefinition>>> predicatesByRoute = this.predicateService
				.loadPredicateDefinitionsByRouteIds(routeIds);

			Mono<Map<Long, List<FilterDefinition>>> filtersByRoute = this.filterService
				.loadFilterDefinitionsByRouteIds(routeIds);

			return Mono.zip(predicatesByRoute, filtersByRoute).flatMapMany((tuple) -> {
				Map<Long, List<PredicateDefinition>> predicateMap = tuple.getT1();
				Map<Long, List<FilterDefinition>> filterMap = tuple.getT2();

				return Flux.fromIterable(routes).map((route) -> {
					RouteDefinition routeDefinition = new RouteDefinition();
					routeDefinition.setId(route.getRouteId());
					routeDefinition.setUri(URI.create(route.getUri()));
					if (route.getOrder() != null) {
						routeDefinition.setOrder(route.getOrder());
					}
					routeDefinition.setPredicates(predicateMap.getOrDefault(route.getId(), List.of()));
					routeDefinition.setFilters(filterMap.getOrDefault(route.getId(), List.of()));

					return routeDefinition;
				});
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
		return this.routeRepository.existsByRouteId(routeModel.routeId()).flatMap((exists) -> {
			if (exists) {
				return Mono.error(new RouteAlreadyExistException());
			}
			else {
				return Mono
					.zip(this.predicateService.validatePredicatesArgs(routeModel.predicates()),
							this.filterService.validateFiltersArgs(routeModel.filters()))
					.flatMap((tuple) -> {
						// Crée l'entité après validation des arguments
						RouteEntity routeEntity = new RouteEntity();
						routeEntity.setRouteId(routeModel.routeId());
						routeEntity.setUri(routeModel.uri().toASCIIString());
						routeEntity.setOrder(routeModel.order());

						return this.save(routeEntity)
							.flatMap(createPredicates(routeModel))
							.flatMap(createFilters(routeModel));
					});
			}
		})
			.doOnNext((routeEntity) -> this.publisher.publishEvent(new RefreshRoutesEvent(this)))
			.subscribeOn(Schedulers.boundedElastic());
	}

	public Mono<RouteEntity> updateRoute(Long routeId, @Valid RouteCreateModel routeModel) {
		return Mono
			.zip(this.findById(routeId).switchIfEmpty(Mono.error(new RouteNotFoundException())),
					this.predicateService.validatePredicatesArgs(routeModel.predicates()),
					this.filterService.validateFiltersArgs(routeModel.filters()))
			.flatMap((tuple3) -> {
				RouteEntity routeEntity = tuple3.getT1();
				routeEntity.setRouteId(routeModel.routeId());
				routeEntity.setUri(routeModel.uri().toASCIIString());
				routeEntity.setOrder(routeModel.order());
				return this.save(routeEntity)
					.flatMap(deletePredicates())
					.flatMap(deleteFilters())
					.flatMap(createPredicates(routeModel))
					.flatMap(createFilters(routeModel))
					.doOnNext((r) -> this.publisher.publishEvent(new RefreshRoutesEvent(this)));
			})
			.subscribeOn(Schedulers.boundedElastic());
	}

	public Mono<Void> deleteRoute(Long routeId) {
		return this.findById(routeId)
			.switchIfEmpty(Mono.error(new RouteNotFoundException()))
			.flatMap((routeEntity) -> this.routeRepository.deleteById(routeId));
	}

	private Function<RouteEntity, Mono<RouteEntity>> deletePredicates() {
		return (routeEntity) -> this.predicateService.deleteByRouteId(routeEntity.getId())
			.map((__) -> routeEntity)
			.switchIfEmpty(Mono.just(routeEntity));
	}

	private Function<RouteEntity, Mono<RouteEntity>> deleteFilters() {
		return (routeEntity) -> this.filterService.deleteByRouteId(routeEntity.getId())
			.map((__) -> routeEntity)
			.switchIfEmpty(Mono.just(routeEntity));
	}

	private Function<RouteEntity, Mono<RouteEntity>> createFilters(RouteCreateModel routeModel) {
		return (routeEntity) -> this.filterService.createFilters(routeEntity, routeModel.filters())
			.collectList()
			.map((l) -> routeEntity)
			.defaultIfEmpty(routeEntity);
	}

	private Function<RouteEntity, Mono<RouteEntity>> createPredicates(RouteCreateModel routeModel) {
		return (routeEntity) -> this.predicateService.createPredicates(routeEntity, routeModel.predicates())
			.collectList()
			.map((l) -> routeEntity)
			.defaultIfEmpty(routeEntity);
	}

}
