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

package ch.nexsol.gateway.database.controller;

import ch.nexsol.gateway.database.model.RouteCreateModel;
import ch.nexsol.gateway.database.model.RouteResponseModel;
import ch.nexsol.gateway.database.service.ApiService;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/gateway/routes")
@Validated
public class RouteController {

	private final ApiService apiService;

	public RouteController(ApiService apiService) {
		this.apiService = apiService;
	}

	@GetMapping
	public Flux<RouteResponseModel> getAllRoutes() {
		return this.apiService.getAllRoutes();
	}

	@GetMapping("/{id}")
	public Mono<ResponseEntity<RouteResponseModel>> getRoute(@PathVariable Long id) {
		return this.apiService.findById(id).map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
	}

	@PostMapping
	public Mono<ResponseEntity<RouteResponseModel>> createRoute(@RequestBody @Valid RouteCreateModel routeModel) {
		return this.apiService.createRoute(routeModel).map(ResponseEntity::ok);
	}

	@PutMapping("/{id}")
	public Mono<ResponseEntity<RouteResponseModel>> updateRoute(@PathVariable Long id,
			@RequestBody @Valid RouteCreateModel routeModel) {
		return this.apiService.updateRoute(id, routeModel).map(ResponseEntity::ok);
	}

	@DeleteMapping("/{id}")
	public Mono<ResponseEntity<RouteResponseModel>> deleteRoute(@PathVariable Long id) {
		return this.apiService.deleteRoute(id).then(Mono.just(ResponseEntity.ok().build()));
	}

}
