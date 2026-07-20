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

package ch.nexsol.gateway.database.controller;

import ch.nexsol.gateway.database.model.RouteCreateModel;
import ch.nexsol.gateway.database.model.RouteResponseModel;
import ch.nexsol.gateway.database.service.ApiService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing CRUD operations on gateway routes under
 * {@code /api/gateway/routes}.
 */
@RestController
@RequestMapping("/api/gateway/routes")
@Validated
public class RouteController {

	private static final Logger LOG = LoggerFactory.getLogger(RouteController.class);

	private final ApiService apiService;

	/**
	 * Creates the controller with the API service it delegates to.
	 * @param apiService the API service
	 */
	public RouteController(ApiService apiService) {
		this.apiService = apiService;
	}

	/**
	 * Returns all configured routes.
	 * @return the route response models
	 */
	@GetMapping
	public Flux<RouteResponseModel> getAllRoutes() {
		return this.apiService.getAllRoutes();
	}

	/**
	 * Returns a single route by id.
	 * @param id the route id
	 * @return the route wrapped in a 200 response, or a 404 response when it does not
	 * exist
	 */
	@GetMapping("/{id}")
	public Mono<ResponseEntity<RouteResponseModel>> getRoute(@PathVariable Long id) {
		return this.apiService.findById(id).map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
	}

	/**
	 * Creates a new route.
	 * @param routeModel the route creation payload
	 * @return the created route wrapped in a 200 response
	 */
	@PostMapping
	public Mono<ResponseEntity<RouteResponseModel>> createRoute(@RequestBody @Valid RouteCreateModel routeModel) {
		return this.apiService.createRoute(routeModel)
			.map(ResponseEntity::ok)
			.doOnError((error) -> LOG.error(error.getMessage(), error));
	}

	/**
	 * Updates an existing route.
	 * @param id the id of the route to update
	 * @param routeModel the new route payload
	 * @return the updated route wrapped in a 200 response
	 */
	@PutMapping("/{id}")
	public Mono<ResponseEntity<RouteResponseModel>> updateRoute(@PathVariable Long id,
			@RequestBody @Valid RouteCreateModel routeModel) {
		return this.apiService.updateRoute(id, routeModel)
			.map(ResponseEntity::ok)
			.doOnError((error) -> LOG.error(error.getMessage(), error));
	}

	/**
	 * Deletes the route with the given id.
	 * @param id the id of the route to delete
	 * @return a 200 response once the route is deleted
	 */
	@DeleteMapping("/{id}")
	public Mono<ResponseEntity<RouteResponseModel>> deleteRoute(@PathVariable Long id) {
		return this.apiService.deleteRoute(id).then(Mono.just(ResponseEntity.ok().build()));
	}

}
