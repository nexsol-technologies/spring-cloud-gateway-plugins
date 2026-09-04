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

import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Registers, for a native image, the reflection this module reads its filter factory and
 * the parsed contracts through.
 * <p>
 * Spring Cloud Gateway contributes the factory hints for its own factories from
 * {@code ConfigurableHintsRegistrationProcessor}, but that processor scans the
 * {@code org.springframework.cloud.gateway} package only: a factory declared anywhere
 * else is invisible to it and its {@code Config} has to be registered here, or the
 * shortcut arguments of every route using it fail to bind once the image is built.
 * <p>
 * The OpenAPI model classes are reached reflectively from both ends and ship no
 * reachability metadata of their own: {@code OpenAPIDeserializer} builds them with
 * {@code ObjectMapper.convertValue}, and this module turns a {@code Schema} back into a
 * JSON tree with {@code valueToTree} before handing it to the schema validator. They are
 * registered by scanning the model package rather than listed one by one: there are
 * eighty of them, a schema subclass per JSON type, and the set grows with every OpenAPI
 * revision the library follows.
 */
class OpenapiValidationRuntimeHints implements RuntimeHintsRegistrar {

	private static final String OPENAPI_MODEL_PACKAGE = "io.swagger.v3.oas.models";

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		hints.reflection()
			.registerType(OpenapiValidationGatewayFilterFactory.Config.class, MemberCategory.ACCESS_DECLARED_FIELDS,
					MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
		for (String modelClass : openapiModelClasses(classLoader)) {
			hints.reflection()
				.registerTypeIfPresent(classLoader, modelClass, MemberCategory.ACCESS_DECLARED_FIELDS,
						MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
		}
	}

	private static List<String> openapiModelClasses(ClassLoader classLoader) {
		// Every class of the package is wanted, including the nested enums, so the
		// candidate check that would keep only concrete top level components is lifted.
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				return true;
			}
		};
		scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
		scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
		List<String> classNames = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(OPENAPI_MODEL_PACKAGE)) {
			if (definition.getBeanClassName() != null) {
				classNames.add(definition.getBeanClassName());
			}
		}
		return classNames;
	}

}
