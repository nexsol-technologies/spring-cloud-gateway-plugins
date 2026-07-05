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

import ch.nexsol.gateway.database.entity.FilterEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.exception.FiltersNotValidException;
import ch.nexsol.gateway.database.model.FilterCreateModel;
import ch.nexsol.gateway.database.repository.FilterRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.FilterDefinition;

/**
 * Route element service handling the persistence, validation and gateway-definition
 * mapping of route filters.
 */
public class FilterService
		extends AbstractRouteElementService<FilterEntity, FilterDefinition, FilterCreateModel, FilterRepository> {

	/**
	 * Creates the filter service with its collaborating beans.
	 * @param gatewayConfigService the service validating filter names and arguments
	 * @param filterRepository the repository persisting and querying filters
	 * @param argumentService the service converting arguments to and from their JSON form
	 */
	public FilterService(GatewayConfigService gatewayConfigService, FilterRepository filterRepository,
			ArgumentService argumentService) {
		super(gatewayConfigService, filterRepository, argumentService);
	}

	/**
	 * Validates the arguments of every filter, emitting an error if any is invalid.
	 * @param filters the filters to validate, may be {@code null} or empty
	 * @return {@code true} when all filters are valid (or none are provided), otherwise
	 * an error signal
	 */
	public Mono<Boolean> validateFiltersArgs(List<FilterCreateModel> filters) {
		return validateArgs(filters);
	}

	/**
	 * Persists the given filters against the supplied route.
	 * @param routeEntity the owning route
	 * @param filters the filters to create, may be {@code null} or empty
	 * @return the persisted filters, or an empty stream when none are provided
	 */
	public Flux<FilterEntity> createFilters(RouteEntity routeEntity, List<FilterCreateModel> filters) {
		return create(routeEntity, filters);
	}

	/**
	 * Batch-loads the filter definitions for several routes in a single query and groups
	 * them by route id, avoiding the N+1 query pattern on the route-resolution hot path.
	 * @param routeIds the route reference ids to load filters for
	 * @return the filter definitions grouped by route reference id
	 */
	public Mono<Map<Long, List<FilterDefinition>>> loadFilterDefinitionsByRouteIds(Collection<Long> routeIds) {
		return loadDefinitionsByRouteIds(routeIds);
	}

	@Override
	protected FilterEntity newEntity() {
		return new FilterEntity();
	}

	@Override
	protected FilterDefinition toDefinition(String name, Map<String, String> args) {
		FilterDefinition filterDefinition = new FilterDefinition();
		filterDefinition.setName(name);
		filterDefinition.setArgs(args);
		return filterDefinition;
	}

	@Override
	protected Mono<Boolean> validateElement(GatewayConfigService configService, String name, Map<String, String> args) {
		return configService.validateFilter(name, args);
	}

	@Override
	protected RuntimeException notValidException() {
		return new FiltersNotValidException();
	}

	@Override
	protected String elementLabel() {
		return "filter";
	}

}
