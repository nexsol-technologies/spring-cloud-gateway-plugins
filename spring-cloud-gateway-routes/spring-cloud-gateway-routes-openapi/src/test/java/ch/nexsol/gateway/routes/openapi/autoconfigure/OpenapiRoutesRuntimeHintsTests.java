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

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.core.io.support.SpringFactoriesLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link OpenapiRoutesRuntimeHints}.
 * <p>
 * The coverage test scans rather than lists: an OpenAPI model package the library moves
 * fails the build here, which is the only warning there is &mdash; a missing hint costs
 * nothing on the JVM and only surfaces as a contract yielding no route in a native image.
 */
class OpenapiRoutesRuntimeHintsTests {

	private final RuntimeHints hints = new RuntimeHints();

	@BeforeEach
	void registerHints() {
		new OpenapiRoutesRuntimeHints().registerHints(this.hints, getClass().getClassLoader());
	}

	@Test
	void registersTheOpenapiModelTheContractsAreParsedInto() {
		for (Class<?> modelClass : List.of(OpenAPI.class, Components.class, PathItem.class, Content.class, Schema.class,
				Server.class)) {
			assertThat(RuntimeHintsPredicates.reflection()
				.onType(modelClass)
				.withMemberCategories(MemberCategory.ACCESS_DECLARED_FIELDS, MemberCategory.INVOKE_DECLARED_METHODS,
						MemberCategory.INVOKE_DECLARED_CONSTRUCTORS))
				.as("%s is not registered; the model package scan no longer reaches it", modelClass.getName())
				.accepts(this.hints);
		}
	}

	@Test
	void registersTheWholeOpenapiModelPackage() {
		long registered = this.hints.reflection()
			.typeHints()
			.filter((hint) -> hint.getType().getName().startsWith("io.swagger.v3.oas.models"))
			.count();
		assertThat(registered).isGreaterThan(50);
	}

	@Test
	void isContributedThroughAotFactories() {
		List<RuntimeHintsRegistrar> registrars = SpringFactoriesLoader
			.forResourceLocation("META-INF/spring/aot.factories", getClass().getClassLoader())
			.load(RuntimeHintsRegistrar.class);
		assertThat(registrars).hasAtLeastOneElementOfType(OpenapiRoutesRuntimeHints.class);
	}

}
