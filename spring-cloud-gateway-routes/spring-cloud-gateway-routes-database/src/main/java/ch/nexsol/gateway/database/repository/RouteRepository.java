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

package ch.nexsol.gateway.database.repository;

import ch.nexsol.gateway.database.entity.RouteEntity;
import reactor.core.publisher.Mono;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Reactive repository for {@link RouteEntity} routes.
 */
public interface RouteRepository extends ReactiveCrudRepository<RouteEntity, Long> {

	/**
	 * Finds a route by its business route id.
	 * @param routeId the business route id
	 * @return the matching route, or an empty result when none exists
	 */
	Mono<RouteEntity> findByRouteId(String routeId);

	/**
	 * Checks whether a route with the given business route id exists.
	 * @param routeId the business route id
	 * @return {@code true} when a matching route exists
	 */
	Mono<Boolean> existsByRouteId(String routeId);

}
