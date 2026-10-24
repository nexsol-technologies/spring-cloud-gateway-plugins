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

package ch.nexsol.gateway.mcp.server.autoconfigure;

import java.util.List;

import ch.nexsol.gateway.mcp.server.routes.RouteTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.aot.AotServices;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMcpRuntimeHintsTests {

	private final RuntimeHints hints = new RuntimeHints();

	@BeforeEach
	void registerHints() {
		new GatewayMcpRuntimeHints().registerHints(this.hints, getClass().getClassLoader());
	}

	@Test
	void registersTheRecordsTheToolsAnswerWith() {
		for (Class<?> type : List.of(RouteTools.Inventory.class, RouteTools.UnreadableSource.class,
				RouteTools.RouteDescription.class, RouteTools.Step.class, RouteTools.FactoryDescription.class)) {
			assertThat(RuntimeHintsPredicates.reflection().onType(type))
				.as("%s is not registered; add it to GatewayMcpRuntimeHints", type.getName())
				.accepts(this.hints);
		}
	}

	@Test
	void isContributedThroughAotFactories() {
		// The loader the build itself uses, so the resource location cannot drift.
		List<RuntimeHintsRegistrar> registrars = AotServices.factories(getClass().getClassLoader())
			.load(RuntimeHintsRegistrar.class)
			.asList();
		assertThat(registrars).hasAtLeastOneElementOfType(GatewayMcpRuntimeHints.class);
	}

}
