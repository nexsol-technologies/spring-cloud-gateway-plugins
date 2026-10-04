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

package ch.nexsol.gateway.routes.openapi.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aot.hint.ResourceHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.ResourceUtils;

/**
 * Registers, for a native image, the sources files the configuration names on the
 * classpath.
 * <p>
 * An image embeds the resources its reachability metadata lists and no other, and the
 * framework lists what it knows, not a file named by a property of this module. A sources
 * file left out reads as empty and declares no source, with nothing logged. The contracts
 * a sources file names are read at run time and are not known here: a {@code classpath:}
 * contract is the application's to register.
 */
class OpenapiSourcesResourceHints implements BeanFactoryInitializationAotProcessor {

	static final String LOCATIONS_PROPERTY = "spring.cloud.gateway.server.webflux.routes-openapi.sources-locations";

	@Override
	public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
		Environment environment = beanFactory.getBean(Environment.class);
		List<String> locations = Binder.get(environment)
			.bind(LOCATIONS_PROPERTY, Bindable.listOf(String.class))
			.orElse(List.of());
		List<String> patterns = classpathPatterns(locations);
		if (patterns.isEmpty()) {
			return null;
		}
		return (generationContext, beanFactoryInitializationCode) -> {
			ResourceHints resources = generationContext.getRuntimeHints().resources();
			patterns.forEach(resources::registerPattern);
		};
	}

	static List<String> classpathPatterns(List<String> locations) {
		List<String> patterns = new ArrayList<>();
		for (String location : locations) {
			String path = null;
			if (location.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX)) {
				path = location.substring(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX.length());
			}
			else if (location.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX)) {
				path = location.substring(ResourceUtils.CLASSPATH_URL_PREFIX.length());
			}
			if (path != null) {
				patterns.add(path.startsWith("/") ? path.substring(1) : path);
			}
		}
		return patterns;
	}

}
