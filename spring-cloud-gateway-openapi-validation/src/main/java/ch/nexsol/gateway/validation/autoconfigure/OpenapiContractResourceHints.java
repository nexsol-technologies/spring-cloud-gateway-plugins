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

package ch.nexsol.gateway.validation.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory;

import org.springframework.aot.hint.ResourceHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.ResourceUtils;

/**
 * Registers, for a native image, the contracts the routes of the configuration validate
 * against from the classpath.
 * <p>
 * An image embeds the resources its reachability metadata lists and no other, and the
 * framework lists what it knows, not a file named by a filter argument. A contract left
 * out reads as absent, and the filter forwards without validating, with nothing but a
 * debug line to say so. Only the routes declared in the properties are known here: a
 * route read from a database or a file at run time names a contract this cannot see, and
 * a {@code classpath:} contract of such a route is the application's to register.
 */
class OpenapiContractResourceHints implements BeanFactoryInitializationAotProcessor {

	private static final String FILTER_NAME = "OpenapiValidation";

	@Override
	public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
		Environment environment = beanFactory.getBean(Environment.class);
		GatewayProperties gateway = Binder.get(environment)
			.bind(GatewayProperties.PREFIX, GatewayProperties.class)
			.orElseGet(GatewayProperties::new);
		List<FilterDefinition> filters = new ArrayList<>(gateway.getDefaultFilters());
		for (RouteDefinition route : gateway.getRoutes()) {
			filters.addAll(route.getFilters());
		}
		List<String> patterns = classpathContracts(filters);
		if (patterns.isEmpty()) {
			return null;
		}
		return (generationContext, beanFactoryInitializationCode) -> {
			ResourceHints resources = generationContext.getRuntimeHints().resources();
			patterns.forEach(resources::registerPattern);
		};
	}

	/**
	 * The classpath contracts the validation filters name, whether as the first shortcut
	 * argument or under the key of the field, which the binder accepts in either case.
	 */
	static List<String> classpathContracts(List<FilterDefinition> filters) {
		List<String> patterns = new ArrayList<>();
		for (FilterDefinition filter : filters) {
			if (!FILTER_NAME.equals(filter.getName())) {
				continue;
			}
			for (Map.Entry<String, String> arg : filter.getArgs().entrySet()) {
				if (namesTheContract(arg.getKey()) && arg.getValue().startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)) {
					String path = arg.getValue().substring(ResourceUtils.CLASSPATH_URL_PREFIX.length());
					patterns.add(path.startsWith("/") ? path.substring(1) : path);
				}
			}
		}
		return patterns;
	}

	private static boolean namesTheContract(String key) {
		if ((NameUtils.GENERATED_NAME_PREFIX + "0").equals(key)) {
			return true;
		}
		return OpenapiValidationGatewayFilterFactory.SPEC_URL_KEY.toLowerCase(Locale.ROOT)
			.equals(key.replace("-", "").toLowerCase(Locale.ROOT));
	}

}
