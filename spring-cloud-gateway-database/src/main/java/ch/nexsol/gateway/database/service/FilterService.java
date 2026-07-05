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

import ch.nexsol.gateway.database.entity.FilterEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.exception.FiltersNotValidException;
import ch.nexsol.gateway.database.model.FilterCreateModel;
import ch.nexsol.gateway.database.repository.FilterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.FilterDefinition;

public class FilterService {

	private static final Logger LOG = LoggerFactory.getLogger(FilterService.class);

	private final GatewayConfigService gatewayConfigService;

	private final FilterRepository filterRepository;

	private final ArgumentService argumentService;

	public FilterService(GatewayConfigService gatewayConfigService, FilterRepository filterRepository,
			ArgumentService argumentService) {
		this.gatewayConfigService = gatewayConfigService;
		this.filterRepository = filterRepository;
		this.argumentService = argumentService;
	}

	public Flux<FilterEntity> findByRouteId(Long id) {
		return this.filterRepository.findByRouteRefId(id);
	}

	public Mono<Boolean> validateFiltersArgs(List<FilterCreateModel> filters) {
		if (filters != null && !filters.isEmpty()) {
			return Flux.fromIterable(filters)
				.flatMap((filter) -> this.gatewayConfigService.validateFilter(filter.name(), filter.args()))
				.all((valid) -> valid)
				.flatMap((validFilters) -> {
					if (!validFilters) {
						LOG.error("Some filters have bad arguments");
						return Mono.error(new FiltersNotValidException());
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

	public Flux<FilterEntity> createFilters(RouteEntity routeEntity, List<FilterCreateModel> filters) {
		if (filters != null && !filters.isEmpty()) {
			return Flux.fromIterable(filters).flatMap((f) -> {
				FilterEntity filterEntity = new FilterEntity();
				filterEntity.setName(f.name());
				filterEntity.setArgs(this.argumentService.mapArgumentsToJsonString(f.args()));
				filterEntity.setRouteRefId(routeEntity.getId());
				return this.filterRepository.save(filterEntity);
			});
		}
		else {
			return Flux.empty();
		}
	}

	public Mono<Void> deleteByRouteId(Long id) {
		return this.filterRepository.deleteByRouteRefId(id);
	}

	public Mono<List<FilterDefinition>> loadSpringCloudGatewayFilterDefinition(Long routeId) {
		return this.findByRouteId(routeId).map(toFilterDefinition()).collectList();
	}

	/**
	 * Batch-loads the filter definitions for several routes in a single query and groups
	 * them by route id, avoiding the N+1 query pattern on the route-resolution hot path.
	 * @param routeIds the route reference ids to load filters for
	 * @return the filter definitions grouped by route reference id
	 */
	public Mono<Map<Long, List<FilterDefinition>>> loadFilterDefinitionsByRouteIds(Collection<Long> routeIds) {
		if (routeIds == null || routeIds.isEmpty()) {
			return Mono.just(Map.of());
		}
		return this.filterRepository.findByRouteRefIdIn(routeIds)
			.collectList()
			.map((entities) -> entities.stream()
				.collect(Collectors.groupingBy(FilterEntity::getRouteRefId,
						Collectors.mapping(toFilterDefinition(), Collectors.toList()))));
	}

	private Function<FilterEntity, FilterDefinition> toFilterDefinition() {
		return (filter) -> {
			try {
				FilterDefinition filterDefinition = new FilterDefinition();
				filterDefinition.setName(filter.getName());
				filterDefinition.setArgs(this.argumentService.jsonStringArgumentsToMap(filter.getArgs()));
				return filterDefinition;
			}
			catch (Exception ex) {
				throw new RuntimeException("Error deserializing filter args", ex);
			}
		};
	}

}
