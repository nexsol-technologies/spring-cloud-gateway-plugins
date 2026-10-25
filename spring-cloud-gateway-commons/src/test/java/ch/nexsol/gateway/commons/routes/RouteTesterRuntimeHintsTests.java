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

package ch.nexsol.gateway.commons.routes;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.beans.factory.aot.AotServices;

import static org.assertj.core.api.Assertions.assertThat;

class RouteTesterRuntimeHintsTests {

	@Test
	void registersTheRecordsTheTesterAnswersWith() {
		RuntimeHints hints = new RuntimeHints();
		new RouteTesterRuntimeHints().registerHints(hints, getClass().getClassLoader());
		for (Class<?> type : List.of(RouteTestReport.class, RouteMatch.class, PredicateOutcome.class)) {
			assertThat(RuntimeHintsPredicates.reflection().onType(type)).accepts(hints);
		}
	}

	@Test
	void isContributedThroughAotFactories() {
		List<RuntimeHintsRegistrar> registrars = AotServices.factories(getClass().getClassLoader())
			.load(RuntimeHintsRegistrar.class)
			.asList();
		assertThat(registrars).hasAtLeastOneElementOfType(RouteTesterRuntimeHints.class);
	}

}
