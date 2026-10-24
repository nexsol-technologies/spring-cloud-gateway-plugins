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

package ch.nexsol.gateway.commons.routes;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import reactor.core.publisher.Mono;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.route.CompositeRouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.util.ClassUtils;

/**
 * The route definition sources of a gateway, read the same way by every plugin that lists
 * them, so that the console, the analyser and the MCP server name a source alike.
 * <p>
 * Spring Cloud Gateway wraps the locators of the context in a
 * {@link CompositeRouteDefinitionLocator} that is itself a bean: taken as a source, it
 * would repeat every route of the others.
 */
public final class RouteDefinitionSources {

	private static final List<String> LOCATOR_SUFFIXES = List.of("RouteDefinitionLocator", "RouteDefinitionRepository",
			"RouteDefinitionSource");

	private static final Pattern CAMEL_CASE_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

	private RouteDefinitionSources() {
	}

	/**
	 * The sources among the locators of the context, in order, the composite left out.
	 * @param locators the locators of the context
	 * @return the sources
	 */
	public static List<RouteDefinitionLocator> sources(ObjectProvider<RouteDefinitionLocator> locators) {
		return sources(locators.orderedStream().toList());
	}

	/**
	 * The sources among the given locators, in order, the composite left out.
	 * @param locators the locators
	 * @return the sources
	 */
	public static List<RouteDefinitionLocator> sources(List<RouteDefinitionLocator> locators) {
		return locators.stream().filter((locator) -> !(locator instanceof CompositeRouteDefinitionLocator)).toList();
	}

	/**
	 * The name a source is shown under: its class name without the locator suffix, split
	 * into words, and {@code Custom} for a locator declared as a lambda.
	 * @param locator the source
	 * @return its name
	 */
	public static String name(RouteDefinitionLocator locator) {
		String name = ClassUtils.getUserClass(locator).getSimpleName();
		if (name.isEmpty() || name.contains("$$Lambda")) {
			return "Custom";
		}
		for (String suffix : LOCATOR_SUFFIXES) {
			if (name.length() > suffix.length() && name.endsWith(suffix)) {
				name = name.substring(0, name.length() - suffix.length());
				break;
			}
		}
		return CAMEL_CASE_BOUNDARY.matcher(name).replaceAll(" ");
	}

	/**
	 * The definitions of a source, read within a delay that bounds the whole read rather
	 * than the gap between two of its routes.
	 * @param locator the source
	 * @param timeout the delay the whole read must fit in
	 * @return the definitions, or the error the source raised
	 */
	public static Mono<List<RouteDefinition>> read(RouteDefinitionLocator locator, Duration timeout) {
		return locator.getRouteDefinitions().collectList().timeout(timeout);
	}

}
