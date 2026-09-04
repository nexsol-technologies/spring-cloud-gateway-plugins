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

package ch.nexsol.gateway.audit.autoconfigure;

import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cloud.gateway.support.Configurable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GatewayAuditRuntimeHints}.
 * <p>
 * The coverage test scans the module rather than listing the factories: a factory added
 * later is picked up here and fails the build until its config is registered, which is
 * the only warning there is &mdash; a missing hint costs nothing on the JVM and only
 * surfaces as a route that will not bind in a native image.
 */
class GatewayAuditRuntimeHintsTests {

	private static final CodeSource MODULE_CLASSES = GatewayAuditRuntimeHints.class.getProtectionDomain()
		.getCodeSource();

	private final RuntimeHints hints = new RuntimeHints();

	@BeforeEach
	void registerHints() {
		new GatewayAuditRuntimeHints().registerHints(this.hints, getClass().getClassLoader());
	}

	@Test
	void registersTheConfigOfEveryFilterFactoryOfTheModule() {
		List<Class<?>> configClasses = configClassesOf("ch.nexsol.gateway.audit");
		assertThat(configClasses).isNotEmpty();
		for (Class<?> configClass : configClasses) {
			assertThat(RuntimeHintsPredicates.reflection()
				.onType(configClass)
				.withMemberCategories(MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
						MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
				.as("%s is not registered; add it to GatewayAuditRuntimeHints", configClass.getName())
				.accepts(this.hints);
		}
	}

	@Test
	void isContributedThroughAotFactories() {
		List<RuntimeHintsRegistrar> registrars = SpringFactoriesLoader
			.forResourceLocation("META-INF/spring/aot.factories", getClass().getClassLoader())
			.load(RuntimeHintsRegistrar.class);
		assertThat(registrars).hasAtLeastOneElementOfType(GatewayAuditRuntimeHints.class);
	}

	private static List<Class<?>> configClassesOf(String basePackage) {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AssignableTypeFilter(Configurable.class));
		List<Class<?>> configClasses = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
			Class<?> factoryClass = ClassUtils.resolveClassName(definition.getBeanClassName(), null);
			if (!isShippedByThisModule(factoryClass)) {
				continue;
			}
			Class<?> configClass = ResolvableType.forClass(factoryClass).as(Configurable.class).getGeneric(0).resolve();
			if (configClass != null) {
				configClasses.add(configClass);
			}
		}
		return configClasses;
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
