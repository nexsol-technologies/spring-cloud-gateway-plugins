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
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.stereotype.Service;

/**
 * Provides read-only access to the gateway predicate and filter factories available in
 * the application context and validates predicate/filter names and arguments against
 * them.
 */
@Service
public class GatewayConfigService {

	private final ConfigurationService configurationService;

	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();

	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();

	/**
	 * Creates the service by indexing the available filter and predicate factories by
	 * name.
	 * @param configurationService the gateway configuration binder used to validate
	 * predicate arguments
	 * @param gatewayFilters the available gateway filter factories
	 * @param predicates the available route predicate factories
	 */
	public GatewayConfigService(ConfigurationService configurationService, List<GatewayFilterFactory> gatewayFilters,
			List<RoutePredicateFactory> predicates) {
		this.configurationService = configurationService;
		gatewayFilters.forEach((factory) -> this.gatewayFilterFactories.put(factory.name(), factory));
		predicates.forEach((factory) -> this.predicates.put(factory.name(), factory));
	}

	/**
	 * Lists the names of all available predicate factories.
	 * @return the available predicate names
	 */
	public Flux<CharSequence> getAvailablePredicates() {
		return Flux.fromIterable(this.predicates.values()).map((factory) -> factory.name());
	}

	/**
	 * Lists the names of all available filter factories.
	 * @return the available filter names
	 */
	public Flux<CharSequence> getAvailableFilters() {
		return Flux.fromIterable(this.gatewayFilterFactories.values()).map((factory) -> factory.name());
	}

	/**
	 * Lists all available predicates with their accepted argument names, sorted by name.
	 * @return the available predicates, each as a map holding its {@code name} and
	 * {@code args}
	 */
	public Flux<Map<String, Object>> getAvailablePredicatesWithArgs() {
		return Flux.fromIterable(this.predicates.values())
			.map((factory) -> Map.of("name", factory.name(), "args", factory.shortcutFieldOrder()))
			.sort(Comparator.comparing((map) -> (String) map.get("name")));
	}

	/**
	 * Lists all available filters with their accepted argument names, sorted by name.
	 * @return the available filters, each as a map holding its {@code name} and
	 * {@code args}
	 */
	public Flux<Map<String, Object>> getAvailableFiltersWithArgs() {
		return Flux.fromIterable(this.gatewayFilterFactories.values())
			.map((factory) -> Map.of("name", factory.name(), "args", factory.shortcutFieldOrder()))
			.sort(Comparator.comparing((map) -> (String) map.get("name")));
	}

	/**
	 * Lists the accepted argument names for the named predicate.
	 * @param predicate the predicate name
	 * @return the accepted argument names
	 */
	public Flux<CharSequence> getArgsForPredicate(String predicate) {
		return Flux.fromIterable(this.predicates.values())
			.filter((factory) -> factory.name().equals(predicate))
			.flatMapIterable((factory) -> factory.shortcutFieldOrder());
	}

	/**
	 * Lists the accepted argument names for the named filter.
	 * @param filter the filter name
	 * @return the accepted argument names
	 */
	public Flux<CharSequence> getArgsForFilter(String filter) {
		return Flux.fromIterable(this.gatewayFilterFactories.values())
			.filter((factory) -> factory.name().equals(filter))
			.flatMapIterable((factory) -> factory.shortcutFieldOrder());
	}

	/**
	 * Validates that a filter with the given name exists and that the supplied arguments
	 * cover its required argument names.
	 * @param name the filter name
	 * @param args the supplied filter arguments
	 * @return {@code true} when the filter exists and its arguments are valid, otherwise
	 * {@code false}
	 */
	public Mono<Boolean> validateFilter(String name, Map<String, String> args) {
		// Look the factory up directly from the name-keyed map instead of rebuilding and
		// sorting the whole list of available filters on every call.
		GatewayFilterFactory factory = this.gatewayFilterFactories.get(name);
		if (factory == null) {
			return Mono.just(false);
		}
		List<String> validArgs = factory.shortcutFieldOrder();
		if (!validArgs.isEmpty() && args.keySet().isEmpty()) {
			return Mono.just(false);
		}
		return Mono.just(validArgs.stream().allMatch((a) -> args.keySet().contains(a)));
	}

	/**
	 * Validates that a predicate with the given name exists, that the supplied arguments
	 * cover its required argument names and that they bind to the predicate
	 * configuration.
	 * @param name the predicate name
	 * @param args the supplied predicate arguments
	 * @return {@code true} when the predicate exists and its arguments are valid,
	 * otherwise {@code false}
	 * @throws PredicateArgsFormatException if the arguments cannot be bound to the
	 * predicate configuration
	 */
	public Mono<Boolean> validatePredicate(String name, Map<String, String> args) {
		// Look the factory up directly from the name-keyed map instead of rebuilding and
		// sorting the whole list of available predicates on every call.
		RoutePredicateFactory factory = this.predicates.get(name);
		if (factory == null) {
			return Mono.error(new PredicateNotFoundException(name));
		}
		List<String> validArgs = factory.shortcutFieldOrder();
		if (!validArgs.stream().allMatch((a) -> args.keySet().contains(a))) {
			return Mono.just(false);
		}
		try {
			this.configurationService.with(factory).name(name).properties(args).bind();
		}
		catch (BindException ex) {
			throw new PredicateArgsFormatException(ex.getMessage());
		}
		return Mono.just(true);
	}

}
