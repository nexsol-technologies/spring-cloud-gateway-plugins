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

import java.util.Collection;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Reactive repository base shared by the predicate and filter repositories, exposing the
 * route-scoped lookup and delete operations common to both route element types.
 *
 * @param <E> the route element entity type
 */
@NoRepositoryBean
public interface RouteElementRepository<E> extends ReactiveCrudRepository<E, Long> {

	/**
	 * Finds all elements belonging to the given route.
	 * @param routeId the route reference id
	 * @return the matching elements
	 */
	Flux<E> findByRouteRefId(Long routeId);

	/**
	 * Finds all elements belonging to any of the given routes in a single query.
	 * @param routeIds the route reference ids
	 * @return the matching elements across all requested routes
	 */
	Flux<E> findByRouteRefIdIn(Collection<Long> routeIds);

	/**
	 * Deletes all elements belonging to the given route.
	 * @param routeId the route reference id
	 * @return a completion signal
	 */
	Mono<Void> deleteByRouteRefId(Long routeId);

}
