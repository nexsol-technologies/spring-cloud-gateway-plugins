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

import java.util.List;

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
 * Tests for {@link OpenapiSourcesResourceHints}.
 */
class OpenapiSourcesResourceHintsTests {

	@Test
	void registersEveryClasspathLocationAsAResourceOfTheImage() {
		RuntimeHints hints = contribute("classpath:openapi/sources.yaml", "file:/etc/gateway/sources.yaml");
		assertThat(RuntimeHintsPredicates.resource().forResource("openapi/sources.yaml")).accepts(hints);
		assertThat(RuntimeHintsPredicates.resource().forResource("etc/gateway/sources.yaml")).rejects(hints);
	}

	@Test
	void contributesNothingWhenNoLocationIsOnTheClasspath() {
		assertThat(
				new OpenapiSourcesResourceHints().processAheadOfTime(beanFactoryWith("https://registry/sources.yaml")))
			.isNull();
	}

	@Test
	void isContributedThroughAotFactories() {
		List<BeanFactoryInitializationAotProcessor> processors = AotServices.factories(getClass().getClassLoader())
			.load(BeanFactoryInitializationAotProcessor.class)
			.asList();
		assertThat(processors).hasAtLeastOneElementOfType(OpenapiSourcesResourceHints.class);
	}

	private static RuntimeHints contribute(String... locations) {
		BeanFactoryInitializationAotContribution contribution = new OpenapiSourcesResourceHints()
			.processAheadOfTime(beanFactoryWith(locations));
		DefaultGenerationContext generationContext = new DefaultGenerationContext(
				new ClassNameGenerator(ClassName.get("com.example", "Probe")), new InMemoryGeneratedFiles());
		contribution.applyTo(generationContext, null);
		return generationContext.getRuntimeHints();
	}

	private static DefaultListableBeanFactory beanFactoryWith(String... locations) {
		MockEnvironment environment = new MockEnvironment();
		for (int i = 0; i < locations.length; i++) {
			environment.setProperty(OpenapiSourcesResourceHints.LOCATIONS_PROPERTY + "[" + i + "]", locations[i]);
		}
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		beanFactory.registerSingleton("environment", environment);
		return beanFactory;
	}

}
