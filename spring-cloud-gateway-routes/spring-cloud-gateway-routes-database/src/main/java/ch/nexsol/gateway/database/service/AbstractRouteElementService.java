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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import ch.nexsol.gateway.database.entity.RouteElementEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.model.RouteElementCreateModel;
import ch.nexsol.gateway.database.repository.RouteElementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Shared reactive logic for the route element services (predicates and filters). It
 * concentrates the structurally identical persistence, validation and gateway-definition
 * mapping code and delegates the type-specific parts (entity/definition creation, the
 * validation call and the not-valid exception) to abstract hook methods implemented by
 * the concrete subclasses.
 *
 * @param <E> the persisted route element entity type
 * @param <D> the Spring Cloud Gateway definition type produced from the entity
 * @param <M> the creation model type carrying the element name and arguments
 * @param <R> the repository type used to persist and query the entity
 */
public abstract class AbstractRouteElementService<E extends RouteElementEntity, D, M extends RouteElementCreateModel, R extends RouteElementRepository<E>> {

	private static final Logger LOG = LoggerFactory.getLogger(AbstractRouteElementService.class);

	private final GatewayConfigService gatewayConfigService;

	private final R repository;

	private final ArgumentService argumentService;

	/**
	 * Creates the service with its collaborating beans.
	 * @param gatewayConfigService the service validating element names and arguments
	 * @param repository the repository persisting and querying the elements
	 * @param argumentService the service converting arguments to and from their JSON form
	 */
	protected AbstractRouteElementService(GatewayConfigService gatewayConfigService, R repository,
			ArgumentService argumentService) {
		this.gatewayConfigService = gatewayConfigService;
		this.repository = repository;
		this.argumentService = argumentService;
	}

	/**
	 * Finds all elements attached to the given route.
	 * @param id the route reference id
	 * @return the elements attached to the route
	 */
	public Flux<E> findByRouteId(Long id) {
		return this.repository.findByRouteRefId(id);
	}

	/**
	 * Deletes all elements attached to the given route.
	 * @param id the route reference id
	 * @return a completion signal
	 */
	public Mono<Void> deleteByRouteId(Long id) {
		return this.repository.deleteByRouteRefId(id);
	}

	/**
	 * Validates the arguments of every element, emitting an error if any is invalid.
	 * @param elements the elements to validate, may be {@code null} or empty
	 * @return {@code true} when all elements are valid (or none are provided), otherwise
	 * an error signal carrying the not-valid exception
	 */
	protected Mono<Boolean> validateArgs(List<M> elements) {
		if (elements != null && !elements.isEmpty()) {
			return Flux.fromIterable(elements)
				.flatMap((element) -> validateElement(this.gatewayConfigService, element.name(), element.args()))
				.all((valid) -> valid)
				.flatMap((allValid) -> {
					if (!allValid) {
						LOG.error("Some {}s have bad arguments", elementLabel());
						return Mono.error(notValidException());
					}
					else {
						return Mono.just(true);
					}
				});
		}
		else {
			return Mono.just(true);
		}
	}

	/**
	 * Persists the given elements against the supplied route.
	 * @param routeEntity the owning route
	 * @param elements the elements to create, may be {@code null} or empty
	 * @return the persisted elements, or an empty stream when none are provided
	 */
	protected Flux<E> create(RouteEntity routeEntity, List<M> elements) {
		if (elements != null && !elements.isEmpty()) {
			return Flux.fromIterable(elements).flatMap((element) -> {
				E entity = newEntity();
				entity.setName(element.name());
				entity.setArgs(this.argumentService.mapArgumentsToJsonString(element.args()));
				entity.setRouteRefId(routeEntity.getId());
				return this.repository.save(entity);
			});
		}
		else {
			return Flux.empty();
		}
	}

	/**
	 * Batch-loads the gateway definitions for several routes in a single query and groups
	 * them by route id, avoiding the N+1 query pattern on the route-resolution hot path.
	 * @param routeIds the route reference ids to load elements for
	 * @return the gateway definitions grouped by route reference id
	 */
	protected Mono<Map<Long, List<D>>> loadDefinitionsByRouteIds(Collection<Long> routeIds) {
		if (routeIds == null || routeIds.isEmpty()) {
			return Mono.just(Map.of());
		}
		return this.repository.findByRouteRefIdIn(routeIds)
			.collectList()
			.map((entities) -> entities.stream()
				.collect(Collectors.groupingBy(RouteElementEntity::getRouteRefId,
						Collectors.mapping(toDefinitionFunction(), Collectors.toList()))));
	}

	private Function<E, D> toDefinitionFunction() {
		return (entity) -> {
			try {
				return toDefinition(entity.getName(), this.argumentService.jsonStringArgumentsToMap(entity.getArgs()));
			}
			catch (Exception ex) {
				throw new RuntimeException("Error deserializing " + elementLabel() + " args", ex);
			}
		};
	}

	/**
	 * Creates a new, empty persistence entity for this element type.
	 * @return a new entity instance
	 */
	protected abstract E newEntity();

	/**
	 * Builds the Spring Cloud Gateway definition for this element type.
	 * @param name the element name
	 * @param args the deserialized element arguments
	 * @return the gateway definition
	 */
	protected abstract D toDefinition(String name, Map<String, String> args);

	/**
	 * Validates a single element against the gateway configuration.
	 * @param configService the gateway configuration service to validate against
	 * @param name the element name
	 * @param args the element arguments
	 * @return {@code true} when the element is valid
	 */
	protected abstract Mono<Boolean> validateElement(GatewayConfigService configService, String name,
			Map<String, String> args);

	/**
	 * Supplies the exception raised when at least one element has invalid arguments.
	 * @return the not-valid exception to emit
	 */
	protected abstract RuntimeException notValidException();

	/**
	 * Returns the singular label of this element type ({@code "filter"} or
	 * {@code "predicate"}) used in log and error messages.
	 * @return the element label
	 */
	protected abstract String elementLabel();

}
