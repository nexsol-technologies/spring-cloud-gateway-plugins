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

import ch.nexsol.gateway.database.entity.PredicateEntity;
import ch.nexsol.gateway.database.entity.RouteEntity;
import ch.nexsol.gateway.database.exception.PredicatesNotValidException;
import ch.nexsol.gateway.database.model.PredicateCreateModel;
import ch.nexsol.gateway.database.repository.PredicateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.stereotype.Service;

@Service
public class PredicateService {

	private static final Logger LOG = LoggerFactory.getLogger(PredicateService.class);

	private final GatewayConfigService gatewayConfigService;

	private final PredicateRepository predicateRepository;

	private final ArgumentService argumentService;

	public PredicateService(GatewayConfigService gatewayConfigService, PredicateRepository predicateRepository,
			ArgumentService argumentService) {
		this.gatewayConfigService = gatewayConfigService;
		this.predicateRepository = predicateRepository;
		this.argumentService = argumentService;
	}

	public Flux<PredicateEntity> findByRouteId(Long id) {
		return this.predicateRepository.findByRouteRefId(id);
	}

	public Mono<Boolean> validatePredicatesArgs(List<PredicateCreateModel> predicates) {
		if (predicates != null && !predicates.isEmpty()) {
			return Flux.fromIterable(predicates)
				.flatMap((predicate) -> this.gatewayConfigService.validatePredicate(predicate.name(), predicate.args()))
				.all((valid) -> valid)
				.flatMap((validPredicates) -> {
					if (!validPredicates) {
						LOG.error("Some predicates have bad arguments");
						return Mono.error(new PredicatesNotValidException());
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

	public Flux<PredicateEntity> createPredicates(RouteEntity routeEntity, List<PredicateCreateModel> predicates) {
		if (predicates != null && !predicates.isEmpty()) {
			return Flux.fromIterable(predicates).flatMap((p) -> {
				PredicateEntity predicateEntity = new PredicateEntity();
				predicateEntity.setName(p.name());
				predicateEntity.setArgs(this.argumentService.mapArgumentsToJsonString(p.args()));
				predicateEntity.setRouteRefId(routeEntity.getId());
				return this.predicateRepository.save(predicateEntity);
			});
		}
		else {
			return Flux.empty();
		}
	}

	public Mono<Void> deleteByRouteId(Long id) {
		return this.predicateRepository.deleteByRouteRefId(id);
	}

	public Mono<List<PredicateDefinition>> loadSpringCloudGatewayPredicateDefinition(Long routeId) {
		return this.findByRouteId(routeId).map(toPredicateDefinition()).collectList();
	}

	/**
	 * Batch-loads the predicate definitions for several routes in a single query and
	 * groups them by route id, avoiding the N+1 query pattern on the route-resolution hot
	 * path.
	 * @param routeIds the route reference ids to load predicates for
	 * @return the predicate definitions grouped by route reference id
	 */
	public Mono<Map<Long, List<PredicateDefinition>>> loadPredicateDefinitionsByRouteIds(Collection<Long> routeIds) {
		if (routeIds == null || routeIds.isEmpty()) {
			return Mono.just(Map.of());
		}
		return this.predicateRepository.findByRouteRefIdIn(routeIds)
			.collectList()
			.map((entities) -> entities.stream()
				.collect(Collectors.groupingBy(PredicateEntity::getRouteRefId,
						Collectors.mapping(toPredicateDefinition(), Collectors.toList()))));
	}

	private Function<PredicateEntity, PredicateDefinition> toPredicateDefinition() {
		return (predicate) -> {
			try {
				PredicateDefinition predicateDefinition = new PredicateDefinition();
				predicateDefinition.setName(predicate.getName());
				predicateDefinition.setArgs(this.argumentService.jsonStringArgumentsToMap(predicate.getArgs()));
				return predicateDefinition;
			}
			catch (Exception ex) {
				throw new RuntimeException("Error deserializing predicate args", ex);
			}
		};
	}

}
