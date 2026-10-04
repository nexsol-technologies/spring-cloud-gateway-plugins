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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.aot.generate.ClassNameGenerator;
import org.springframework.aot.generate.DefaultGenerationContext;
import org.springframework.aot.generate.InMemoryGeneratedFiles;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.aot.AotServices;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.javapoet.ClassName;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenapiContractResourceHints}.
 */
class OpenapiContractResourceHintsTests {

	private static final String ROUTES = "spring.cloud.gateway.server.webflux.routes";

	@Test
	void registersTheClasspathContractsTheRoutesValidateAgainst() {
		RuntimeHints hints = contribute(Map.of(ROUTES + "[0].id", "shortcut", ROUTES + "[0].uri",
				"http://localhost:8080", ROUTES + "[0].filters[0]",
				"OpenapiValidation=classpath:openapi/bookstore.yaml,/bookstore", ROUTES + "[1].id", "named",
				ROUTES + "[1].uri", "http://localhost:8080", ROUTES + "[1].filters[0].name", "OpenapiValidation",
				ROUTES + "[1].filters[0].args.spec-url", "classpath:/openapi/library.yaml", ROUTES + "[2].id", "remote",
				ROUTES + "[2].uri", "http://localhost:8080", ROUTES + "[2].filters[0]",
				"OpenapiValidation=https://registry/contract.yaml"));
		assertThat(RuntimeHintsPredicates.resource().forResource("openapi/bookstore.yaml")).accepts(hints);
		assertThat(RuntimeHintsPredicates.resource().forResource("openapi/library.yaml")).accepts(hints);
		assertThat(RuntimeHintsPredicates.resource().forResource("registry/contract.yaml")).rejects(hints);
	}

	@Test
	void registersTheContractOfADefaultFilter() {
		RuntimeHints hints = contribute(Map.of("spring.cloud.gateway.server.webflux.default-filters[0]",
				"OpenapiValidation=classpath:openapi/all.yaml"));
		assertThat(RuntimeHintsPredicates.resource().forResource("openapi/all.yaml")).accepts(hints);
	}

	@Test
	void contributesNothingWhenNoRouteValidatesAClasspathContract() {
		assertThat(new OpenapiContractResourceHints().processAheadOfTime(beanFactoryWith(Map.of(ROUTES + "[0].id",
				"plain", ROUTES + "[0].uri", "http://localhost:8080", ROUTES + "[0].filters[0]", "StripPrefix=1"))))
			.isNull();
	}

	@Test
	void isContributedThroughAotFactories() {
		List<BeanFactoryInitializationAotProcessor> processors = AotServices.factories(getClass().getClassLoader())
			.load(BeanFactoryInitializationAotProcessor.class)
			.asList();
		assertThat(processors).hasAtLeastOneElementOfType(OpenapiContractResourceHints.class);
	}

	private static RuntimeHints contribute(Map<String, String> properties) {
		BeanFactoryInitializationAotContribution contribution = new OpenapiContractResourceHints()
			.processAheadOfTime(beanFactoryWith(properties));
		DefaultGenerationContext generationContext = new DefaultGenerationContext(
				new ClassNameGenerator(ClassName.get("com.example", "Probe")), new InMemoryGeneratedFiles());
		contribution.applyTo(generationContext, null);
		return generationContext.getRuntimeHints();
	}

	private static DefaultListableBeanFactory beanFactoryWith(Map<String, String> properties) {
		MockEnvironment environment = new MockEnvironment();
		properties.forEach(environment::setProperty);
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		beanFactory.registerSingleton("environment", environment);
		return beanFactory;
	}

}
