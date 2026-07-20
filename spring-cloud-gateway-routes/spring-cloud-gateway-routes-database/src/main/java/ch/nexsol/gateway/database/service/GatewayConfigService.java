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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.stereotype.Service;

/**
 * Provides read-only access to the gateway predicate and filter factories available in
 * the application context and validates predicate/filter names and arguments against
 * them.
 */
@Service
public class GatewayConfigService {

	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();

	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();

	/**
	 * Creates the service by indexing the available filter and predicate factories by
	 * name.
	 * @param gatewayFilters the available gateway filter factories
	 * @param predicates the available route predicate factories
	 */
	public GatewayConfigService(List<GatewayFilterFactory> gatewayFilters, List<RoutePredicateFactory> predicates) {
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
	 * Returns the accepted arguments of the named predicate mapped to their default
	 * values, as read from a freshly instantiated predicate configuration.
	 * @param predicate the predicate name
	 * @return the argument names mapped to their default value (empty string when none),
	 * or an empty map when the predicate is unknown
	 */
	public Map<String, String> getDefaultArgsForPredicate(String predicate) {
		RoutePredicateFactory factory = this.predicates.get(predicate);
		if (factory == null) {
			return Map.of();
		}
		return defaultArgs(factory.newConfig(), factory.shortcutFieldOrder());
	}

	/**
	 * Returns the accepted arguments of the named filter mapped to their default values,
	 * as read from a freshly instantiated filter configuration.
	 * @param filter the filter name
	 * @return the argument names mapped to their default value (empty string when none),
	 * or an empty map when the filter is unknown
	 */
	public Map<String, String> getDefaultArgsForFilter(String filter) {
		GatewayFilterFactory factory = this.gatewayFilterFactories.get(filter);
		if (factory == null) {
			return Map.of();
		}
		return defaultArgs(factory.newConfig(), factory.shortcutFieldOrder());
	}

	/**
	 * Returns the names of the strictly required arguments of the named predicate, that
	 * is the ones carrying a bean validation constraint. The other arguments are
	 * optional.
	 * @param predicate the predicate name
	 * @return the required argument names, or an empty set when the predicate is unknown
	 */
	public Set<String> getRequiredArgsForPredicate(String predicate) {
		RoutePredicateFactory factory = this.predicates.get(predicate);
		return (factory != null) ? requiredArgs(factory.getConfigClass(), factory.shortcutFieldOrder()) : Set.of();
	}

	/**
	 * Returns the names of the strictly required arguments of the named filter, that is
	 * the ones carrying a bean validation constraint. The other arguments are optional.
	 * @param filter the filter name
	 * @return the required argument names, or an empty set when the filter is unknown
	 */
	public Set<String> getRequiredArgsForFilter(String filter) {
		GatewayFilterFactory factory = this.gatewayFilterFactories.get(filter);
		return (factory != null) ? requiredArgs(factory.getConfigClass(), factory.shortcutFieldOrder()) : Set.of();
	}

	private static Map<String, String> defaultArgs(Object config, List<String> fieldOrder) {
		BeanWrapper wrapper = new BeanWrapperImpl(config);
		LinkedHashMap<String, String> result = new LinkedHashMap<>();
		for (String field : fieldOrder) {
			String value;
			try {
				value = stringify(wrapper.getPropertyValue(field));
			}
			catch (RuntimeException ex) {
				// Nested or unreadable fields (e.g. a null nested config) have no default
				// to
				// propose; fall back to an empty value.
				value = "";
			}
			result.put(field, value);
		}
		return result;
	}

	private static String stringify(Object value) {
		if (value == null) {
			return "";
		}
		if (value instanceof Collection<?> collection) {
			return collection.stream().map(String::valueOf).collect(Collectors.joining(","));
		}
		if (value.getClass().isArray()) {
			List<String> parts = new ArrayList<>();
			for (int i = 0; i < Array.getLength(value); i++) {
				parts.add(String.valueOf(Array.get(value, i)));
			}
			return String.join(",", parts);
		}
		return String.valueOf(value);
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
		if (!coversRequiredArgs(factory.getConfigClass(), factory.shortcutFieldOrder(), args)) {
			return Mono.just(false);
		}
		// Reject arguments that cannot be bound to the filter configuration (e.g. a
		// non-boolean value for a boolean field).
		return Mono.just(bindsCleanly(factory.getConfigClass(), args));
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
		if (!coversRequiredArgs(factory.getConfigClass(), factory.shortcutFieldOrder(), args)) {
			return Mono.just(false);
		}
		try {
			// Strictly bind the arguments to the predicate configuration so that
			// type-incompatible values (e.g. a non-boolean value for a boolean field) are
			// rejected instead of being silently coerced.
			new Binder(new MapConfigurationPropertySource(args)).bind("", Bindable.of(factory.getConfigClass()));
		}
		catch (BindException ex) {
			return Mono.error(new PredicateArgsFormatException(ex.getMessage()));
		}
		return Mono.just(true);
	}

	/**
	 * Strictly binds the supplied arguments to the given element configuration class,
	 * reporting whether every value can be converted to its target type.
	 * @param configClass the predicate or filter configuration class to bind against
	 * @param args the supplied arguments keyed by field name
	 * @return {@code true} when the arguments bind cleanly, {@code false} when at least
	 * one value cannot be converted to its target type
	 */
	/**
	 * Checks that the supplied arguments only use known argument names and cover the
	 * strictly required ones. A field is considered required only when it carries a bean
	 * validation constraint ({@link NotNull}, {@link NotEmpty} or {@link NotBlank}); the
	 * other fields are optional and fall back to their configuration default. When no
	 * field is strictly required, at least one argument is still expected for an element
	 * that accepts some, to avoid creating an empty predicate or filter.
	 * @param configClass the predicate or filter configuration class
	 * @param fieldOrder the accepted argument names
	 * @param args the supplied arguments
	 * @return {@code true} when the arguments are acceptable
	 */
	private boolean coversRequiredArgs(Class<?> configClass, List<String> fieldOrder, Map<String, String> args) {
		if (!fieldOrder.containsAll(args.keySet())) {
			return false;
		}
		Set<String> required = requiredArgs(configClass, fieldOrder);
		if (required.isEmpty()) {
			return fieldOrder.isEmpty() || !args.isEmpty();
		}
		return args.keySet().containsAll(required);
	}

	private static Set<String> requiredArgs(Class<?> configClass, List<String> fieldOrder) {
		Set<String> required = new LinkedHashSet<>();
		for (String name : fieldOrder) {
			Field field = findField(configClass, name);
			if (field != null && isRequired(field)) {
				required.add(name);
			}
		}
		return required;
	}

	private static Field findField(Class<?> type, String name) {
		Class<?> current = type;
		while (current != null && current != Object.class) {
			try {
				return current.getDeclaredField(name);
			}
			catch (NoSuchFieldException ex) {
				current = current.getSuperclass();
			}
		}
		return null;
	}

	private static boolean isRequired(Field field) {
		return field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(NotEmpty.class)
				|| field.isAnnotationPresent(NotBlank.class);
	}

	private boolean bindsCleanly(Class<?> configClass, Map<String, String> args) {
		try {
			new Binder(new MapConfigurationPropertySource(args)).bind("", Bindable.of(configClass));
			return true;
		}
		catch (BindException ex) {
			return false;
		}
	}

}
