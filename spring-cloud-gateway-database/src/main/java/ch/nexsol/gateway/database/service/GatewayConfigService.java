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

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.stereotype.Service;

@Service
public class GatewayConfigService {

	private final ConfigurationService configurationService;

	private RouteDefinitionRouteLocator routeDefinitionRouteLocator;

	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();

	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();

	public GatewayConfigService(ConfigurationService configurationService, List<GatewayFilterFactory> gatewayFilters,
			List<RoutePredicateFactory> predicates) {
		this.configurationService = configurationService;
		gatewayFilters.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		predicates.forEach(factory -> this.predicates.put(factory.name(), factory));
	}

	public Flux<CharSequence> getAvailablePredicates() {
		return Flux.fromIterable(this.predicates.values()).map((factory) -> factory.name());
	}

	public Flux<CharSequence> getAvailableFilters() {
		return Flux.fromIterable(this.gatewayFilterFactories.values()).map((factory) -> factory.name());
	}

	public Flux<Map<String, Object>> getAvailablePredicatesWithArgs() {
		return Flux.fromIterable(this.predicates.values())
			.map((factory) -> Map.of("name", factory.name(), "args", factory.shortcutFieldOrder()))
			.sort(Comparator.comparing(map -> (String) map.get("name")));
	}

	public Flux<Map<String, Object>> getAvailableFiltersWithArgs() {
		return Flux.fromIterable(this.gatewayFilterFactories.values())
			.map((factory) -> Map.of("name", factory.name(), "args", factory.shortcutFieldOrder()))
			.sort(Comparator.comparing(map -> (String) map.get("name")));
	}

	public Flux<CharSequence> getArgsForPredicate(String predicate) {
		return Flux.fromIterable(this.predicates.values())
			.filter((factory) -> factory.name().equals(predicate))
			.flatMapIterable((factory) -> factory.shortcutFieldOrder());
	}

	public Flux<CharSequence> getArgsForFilter(String filter) {
		return Flux.fromIterable(this.gatewayFilterFactories.values())
			.filter((factory) -> factory.name().equals(filter))
			.flatMapIterable((factory) -> factory.shortcutFieldOrder());
	}

	public Mono<Boolean> validateFilter(String name, Map<String, String> args) {
		return this.getAvailableFiltersWithArgs()
			.filter(filter -> filter.get("name").equals(name))
			.next() // Récupère le premier (et unique) élément correspondant
			.flatMap(filter -> {
				@SuppressWarnings("unchecked")
				List<String> validArgs = (List<String>) filter.get("args");
				if (!validArgs.isEmpty() && args.keySet().isEmpty()) {
					return Mono.just(false);
				}
				return Mono.just(validArgs.stream().allMatch(a -> args.keySet().contains(a)));
			})
			.defaultIfEmpty(false); // Si aucun filtre trouvé avec ce nom, renvoie false
	}

	public Mono<Boolean> validatePredicate(String name, Map<String, String> args) {
		if (!this.predicates.containsKey(name)) {
			return Mono.error(new PredicateNotFoundException(name));
		}
		return this.getAvailablePredicatesWithArgs()
			.filter(predicate -> predicate.get("name").equals(name))
			.next() // Récupère le premier (et unique) élément correspondant
			.flatMap(predicate -> {
				@SuppressWarnings("unchecked")
				var validArgs = (List<String>) predicate.get("args");
				return Mono.just(validArgs.stream().allMatch(a -> args.keySet().contains(a)));
			})
			.map((isValid) -> {
				if (isValid) {
					try {
						RoutePredicateFactory factory = this.predicates.get(name);
						this.configurationService.with(factory).name(name).properties(args).bind();
					}
					catch (BindException ex) {
						throw new PredicateArgsFormatException(ex.getMessage());
					}
					return true;
				}
				return false;
			});
	}

}
