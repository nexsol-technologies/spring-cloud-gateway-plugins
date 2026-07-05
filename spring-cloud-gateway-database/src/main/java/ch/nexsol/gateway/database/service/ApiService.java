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

import java.util.List;

import ch.nexsol.gateway.database.entity.FilterEntity;
import ch.nexsol.gateway.database.entity.PredicateEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.model.FilterResponseModel;
import ch.nexsol.gateway.database.model.PredicateResponseModel;
import ch.nexsol.gateway.database.model.RouteCreateModel;
import ch.nexsol.gateway.database.model.RouteResponseModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.stereotype.Service;

/**
 * Facade service that orchestrates the route, predicate and filter services and
 * translates the database entities into the API response models.
 */
@Service
public class ApiService {

	public final RouteService routeService;

	public final PredicateService predicateService;

	public final FilterService filterService;

	private final ArgumentService argumentService;

	/**
	 * Creates the API service with its collaborating beans.
	 * @param routeService the route service
	 * @param predicateService the predicate service
	 * @param filterService the filter service
	 * @param argumentService the service converting arguments to and from their JSON form
	 */
	public ApiService(RouteService routeService, PredicateService predicateService, FilterService filterService,
			ArgumentService argumentService) {
		this.routeService = routeService;
		this.predicateService = predicateService;
		this.filterService = filterService;
		this.argumentService = argumentService;
	}

	/**
	 * Finds a route by id and maps it to its API response model.
	 * @param id the route id
	 * @return the route response model, or an empty result when none exists
	 */
	public Mono<RouteResponseModel> findById(Long id) {
		return this.routeService.findById(id).flatMap(this::toRouteResponseModel);
	}

	/**
	 * Returns all routes mapped to their API response models.
	 * @return the route response models
	 */
	public Flux<RouteResponseModel> getAllRoutes() {
		return this.routeService.getAllRoutes().flatMap(this::toRouteResponseModel);
	}

	/**
	 * Creates a route from the given payload and maps it to its API response model.
	 * @param routeModel the route creation payload
	 * @return the created route response model
	 */
	public Mono<RouteResponseModel> createRoute(RouteCreateModel routeModel) {
		return this.routeService.createRoute(routeModel).flatMap(this::toRouteResponseModel);
	}

	/**
	 * Updates the route with the given id and maps it to its API response model.
	 * @param id the id of the route to update
	 * @param routeModel the new route payload
	 * @return the updated route response model
	 */
	public Mono<RouteResponseModel> updateRoute(Long id, RouteCreateModel routeModel) {
		return this.routeService.updateRoute(id, routeModel).flatMap(this::toRouteResponseModel);
	}

	/**
	 * Deletes the route with the given id.
	 * @param id the id of the route to delete
	 * @return a completion signal
	 */
	public Mono<Void> deleteRoute(Long id) {
		return this.routeService.deleteRoute(id);
	}

	private Mono<RouteResponseModel> toRouteResponseModel(RouteEntity route) {
		Mono<List<PredicateResponseModel>> predicates = this.predicateService.findByRouteId(route.getId())
			.flatMap(this::toPredicateResponseModel)
			.collectList();
		Mono<List<FilterResponseModel>> filters = this.filterService.findByRouteId(route.getId())
			.flatMap(this::toFilterResponseModel)
			.collectList();
		return Mono.zip(predicates, filters)
			.map((tuple) -> new RouteResponseModel(route.getId(), route.getRouteId(), route.getUri(), route.getOrder(),
					tuple.getT1(), tuple.getT2()));
	}

	private Mono<PredicateResponseModel> toPredicateResponseModel(PredicateEntity predicate) {
		PredicateResponseModel model = new PredicateResponseModel(predicate.getId(), predicate.getName(),
				this.argumentService.jsonStringArgumentsToMap(predicate.getArgs()), predicate.getRouteRefId());
		return Mono.just(model);
	}

	private Mono<FilterResponseModel> toFilterResponseModel(FilterEntity filter) {
		FilterResponseModel model = new FilterResponseModel(filter.getId(), filter.getName(),
				this.argumentService.jsonStringArgumentsToMap(filter.getArgs()), filter.getRouteRefId());
		return Mono.just(model);
	}

}
