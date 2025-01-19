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

import java.util.List;
import java.util.function.Function;

import ch.nexsol.gateway.database.entity.FilterEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.exception.FilterArgsNotReadableException;
import ch.nexsol.gateway.database.exception.FiltersNotValidException;
import ch.nexsol.gateway.database.model.FilterCreateModel;
import ch.nexsol.gateway.database.repository.FilterRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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
				.flatMap(filter -> this.gatewayConfigService.validateFilter(filter.name(), filter.args()))
				.all(valid -> valid)
				.flatMap(validFilters -> {
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
			return Flux.fromIterable(filters).flatMap(f -> {
				try {
					FilterEntity filterEntity = new FilterEntity();
					filterEntity.setName(f.name());
					filterEntity.setArgs(this.argumentService.mapArgumentsToJsonString(f.args()));
					filterEntity.setRouteRefId(routeEntity.getId());
					return this.filterRepository.save(filterEntity);
				}
				catch (JsonProcessingException e) {
					LOG.error("Predicate {} has arguments '{}' which are not readable", f.name(), f.args());
					return Mono.error(new FilterArgsNotReadableException(e));
				}
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

	private Function<FilterEntity, FilterDefinition> toFilterDefinition() {
		return filter -> {
			try {
				FilterDefinition filterDefinition = new FilterDefinition();
				filterDefinition.setName(filter.getName());
				filterDefinition.setArgs(this.argumentService.jsonStringArgumentsToMap(filter.getArgs()));
				return filterDefinition;
			}
			catch (Exception e) {
				throw new RuntimeException("Error deserializing predicate args", e);
			}
		};
	}

}
