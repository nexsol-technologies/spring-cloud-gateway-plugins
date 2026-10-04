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

import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.ReflectionHints;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
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
 * The OpenAPI model is reached reflectively from both ends. This module turns a
 * {@code Schema} back into a JSON tree with {@code Json.mapper().valueToTree} before
 * handing it to the schema validator, and the parser reads the documents an external
 * {@code $ref} points at into model classes with {@code DeserializationUtils}; an inline
 * contract is built by hand. Both go through the mapper swagger-core configures, which
 * applies its mix-ins to the model &mdash; {@code SchemaMixin} is what hides
 * {@code exampleSetFlag} and {@code jsonSchema} &mdash; and Jackson reads a mix-in's
 * annotations off the mix-in class, reflectively too. Both packages are scanned rather
 * than listed: there are eighty model classes, a schema subclass per JSON type, and the
 * set grows with every OpenAPI revision the library follows. The GraalVM reachability
 * metadata repository publishes the same for swagger-core when a build enables it; this
 * registrar makes the module whole without it and adds the nested enums that entry leaves
 * out.
 */
class OpenapiValidationRuntimeHints implements RuntimeHintsRegistrar {

	private static final String[] SWAGGER_PACKAGES = { "io.swagger.v3.oas.models", "io.swagger.v3.core.jackson.mixin" };

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		hints.reflection()
			.registerType(OpenapiValidationGatewayFilterFactory.Config.class, MemberCategory.ACCESS_DECLARED_FIELDS,
					MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
		registerSwaggerClasses(hints.reflection(), classLoader);
	}

	private static void registerSwaggerClasses(ReflectionHints reflection, ClassLoader classLoader) {
		// The mix-ins are abstract classes, which the default candidate check drops, so
		// it is lifted: every class of both packages is wanted.
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				return true;
			}
		};
		scanner.setResourceLoader(new DefaultResourceLoader(classLoader));
		scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
		for (String swaggerPackage : SWAGGER_PACKAGES) {
			for (BeanDefinition definition : scanner.findCandidateComponents(swaggerPackage)) {
				reflection.registerType(TypeReference.of(definition.getBeanClassName()),
						MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
						MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
			}
		}
	}

}
