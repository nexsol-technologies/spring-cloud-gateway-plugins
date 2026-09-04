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

package ch.nexsol.gateway.servicegraph.autoconfigure;

import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ServiceGraphRuntimeHints}.
 * <p>
 * The coverage test scans the module rather than listing the graph types: one added later
 * is picked up here and fails the build until it is registered, which is the only warning
 * there is &mdash; a missing hint costs nothing on the JVM and only surfaces as an empty
 * payload in a native image.
 */
class ServiceGraphRuntimeHintsTests {

	private static final CodeSource MODULE_CLASSES = ServiceGraphRuntimeHints.class.getProtectionDomain()
		.getCodeSource();

	private final RuntimeHints hints = new RuntimeHints();

	@BeforeEach
	void registerHints() {
		new ServiceGraphRuntimeHints().registerHints(this.hints, getClass().getClassLoader());
	}

	@Test
	void registersEveryPublicRecordOfTheModule() {
		List<Class<?>> records = recordsOf("ch.nexsol.gateway.servicegraph");
		assertThat(records).isNotEmpty();
		for (Class<?> record : records) {
			assertThat(RuntimeHintsPredicates.reflection().onType(record))
				.as("%s is not registered; add it to ServiceGraphRuntimeHints", record.getName())
				.accepts(this.hints);
		}
	}

	@Test
	void isContributedThroughAotFactories() {
		List<RuntimeHintsRegistrar> registrars = SpringFactoriesLoader
			.forResourceLocation("META-INF/spring/aot.factories", getClass().getClassLoader())
			.load(RuntimeHintsRegistrar.class);
		assertThat(registrars).hasAtLeastOneElementOfType(ServiceGraphRuntimeHints.class);
	}

	private static List<Class<?>> recordsOf(String basePackage) {
		// The nested records are wanted too, so the candidate check that would keep only
		// top level components is lifted and the record test is applied instead.
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				return true;
			}
		};
		scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
		List<Class<?>> records = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
			Class<?> candidate = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
			if (candidate.isRecord() && Modifier.isPublic(candidate.getModifiers())
					&& isShippedByThisModule(candidate)) {
				records.add(candidate);
			}
		}
		return records;
	}

	private static boolean isShippedByThisModule(Class<?> candidate) {
		// The scan sees the test classpath as well; only what the module ships needs a
		// hint, and a fixture declared in a test would otherwise fail the build. The
		// registrar is the reference: whatever it was loaded from is this module.
		CodeSource codeSource = candidate.getProtectionDomain().getCodeSource();
		return codeSource != null && MODULE_CLASSES != null
				&& codeSource.getLocation().equals(MODULE_CLASSES.getLocation());
	}

}
