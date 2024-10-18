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

import ch.nexsol.gateway.database.entity.FilterEntity;
import ch.nexsol.gateway.database.entity.PredicateEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.exception.PredicateArgsNotReadableException;
import ch.nexsol.gateway.database.model.FilterResponseModel;
import ch.nexsol.gateway.database.model.PredicateResponseModel;
import ch.nexsol.gateway.database.model.RouteCreateModel;
import ch.nexsol.gateway.database.model.RouteResponseModel;
import com.fasterxml.jackson.core.JsonProcessingException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;

/**
 * This service translate Database Entities to API Models
 */
@Service
public class ApiService {

	public final RouteService routeService;

	public final PredicateService predicateService;

	public final FilterService filterService;

	private final ArgumentService argumentService;

	public ApiService(RouteService routeService, PredicateService predicateService, FilterService filterService,
			ArgumentService argumentService) {
		this.routeService = routeService;
		this.predicateService = predicateService;
		this.filterService = filterService;
		this.argumentService = argumentService;
	}

	public Mono<RouteResponseModel> findById(Long id) {
		return this.routeService.findById(id).flatMap(this::toRouteResponseModel);
	}

	public Flux<RouteResponseModel> getAllRoutes() {
		return this.routeService.getAllRoutes()
			.flatMap(route -> this.predicateService.findByRouteId(route.getId())
				.flatMap(this::toPredicateResponseModel)
				.collectList()
				.flatMap(predicates -> this.filterService.findByRouteId(route.getId())
					.flatMap(this::toFilterResponseModel)
					.collectList()
					.map(filters -> new RouteResponseModel(route.getId(), route.getRouteId(), route.getUri(),
							route.getOrder(), predicates, filters))

				));
	}

	public Mono<RouteResponseModel> createRoute(RouteCreateModel routeModel) {
		return this.routeService.createRoute(routeModel).flatMap(this::toRouteResponseModel);
	}

	public Mono<RouteResponseModel> updateRoute(Long id, RouteCreateModel routeModel) {
		return this.routeService.updateRoute(id, routeModel).flatMap(this::toRouteResponseModel);
	}

	public Mono<Void> deleteRoute(Long id) {
		return this.routeService.deleteRoute(id);
	}

	private Mono<RouteResponseModel> toRouteResponseModel(RouteEntity route) {
		return this.predicateService.findByRouteId(route.getId())
			.flatMap(this::toPredicateResponseModel)
			.collectList()
			.flatMap(predicates -> this.filterService.findByRouteId(route.getId())
				.flatMap(this::toFilterResponseModel)
				.collectList()
				.map(filters -> new RouteResponseModel(route.getId(), route.getRouteId(), route.getUri(),
						route.getOrder(), predicates, filters))

			);
	}

	private Mono<PredicateResponseModel> toPredicateResponseModel(PredicateEntity predicate) {
		try {
			PredicateResponseModel model = new PredicateResponseModel(predicate.getId(), predicate.getName(),
					this.argumentService.jsonStringArgumentsToMap(predicate.getArgs()), predicate.getRouteRefId());
			return Mono.just(model);
		}
		catch (JsonProcessingException e) {
			return Mono.error(new PredicateArgsNotReadableException());
		}
	}

	private Mono<FilterResponseModel> toFilterResponseModel(FilterEntity filter) {
		try {
			FilterResponseModel model = new FilterResponseModel(filter.getId(), filter.getName(),
					this.argumentService.jsonStringArgumentsToMap(filter.getArgs()), filter.getRouteRefId());
			return Mono.just(model);
		}
		catch (JsonProcessingException e) {
			return Mono.error(new PredicateArgsNotReadableException());
		}
	}

}
