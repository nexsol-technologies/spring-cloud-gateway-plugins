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

import ch.nexsol.gateway.database.entity.PredicateEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.exception.PredicatesNotValidException;
import ch.nexsol.gateway.database.model.PredicateCreateModel;
import ch.nexsol.gateway.database.repository.PredicateRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.stereotype.Service;

/**
 * Route element service handling the persistence, validation and gateway-definition
 * mapping of route predicates.
 */
@Service
public class PredicateService extends
		AbstractRouteElementService<PredicateEntity, PredicateDefinition, PredicateCreateModel, PredicateRepository> {

	/**
	 * Creates the predicate service with its collaborating beans.
	 * @param gatewayConfigService the service validating predicate names and arguments
	 * @param predicateRepository the repository persisting and querying predicates
	 * @param argumentService the service converting arguments to and from their JSON form
	 */
	public PredicateService(GatewayConfigService gatewayConfigService, PredicateRepository predicateRepository,
			ArgumentService argumentService) {
		super(gatewayConfigService, predicateRepository, argumentService);
	}

	/**
	 * Validates the arguments of every predicate, emitting an error if any is invalid.
	 * @param predicates the predicates to validate, may be {@code null} or empty
	 * @return {@code true} when all predicates are valid (or none are provided),
	 * otherwise an error signal
	 */
	public Mono<Boolean> validatePredicatesArgs(List<PredicateCreateModel> predicates) {
		return validateArgs(predicates);
	}

	/**
	 * Persists the given predicates against the supplied route.
	 * @param routeEntity the owning route
	 * @param predicates the predicates to create, may be {@code null} or empty
	 * @return the persisted predicates, or an empty stream when none are provided
	 */
	public Flux<PredicateEntity> createPredicates(RouteEntity routeEntity, List<PredicateCreateModel> predicates) {
		return create(routeEntity, predicates);
	}

	/**
	 * Batch-loads the predicate definitions for several routes in a single query and
	 * groups them by route id, avoiding the N+1 query pattern on the route-resolution hot
	 * path.
	 * @param routeIds the route reference ids to load predicates for
	 * @return the predicate definitions grouped by route reference id
	 */
	public Mono<Map<Long, List<PredicateDefinition>>> loadPredicateDefinitionsByRouteIds(Collection<Long> routeIds) {
		return loadDefinitionsByRouteIds(routeIds);
	}

	@Override
	protected PredicateEntity newEntity() {
		return new PredicateEntity();
	}

	@Override
	protected PredicateDefinition toDefinition(String name, Map<String, String> args) {
		PredicateDefinition predicateDefinition = new PredicateDefinition();
		predicateDefinition.setName(name);
		predicateDefinition.setArgs(args);
		return predicateDefinition;
	}

	@Override
	protected Mono<Boolean> validateElement(GatewayConfigService configService, String name, Map<String, String> args) {
		return configService.validatePredicate(name, args);
	}

	@Override
	protected RuntimeException notValidException() {
		return new PredicatesNotValidException();
	}

	@Override
	protected String elementLabel() {
		return "predicate";
	}

}
