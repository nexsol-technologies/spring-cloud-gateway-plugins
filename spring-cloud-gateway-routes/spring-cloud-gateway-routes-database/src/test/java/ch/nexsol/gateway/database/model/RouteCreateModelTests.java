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

package ch.nexsol.gateway.database.model;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the target URI a {@link RouteCreateModel} accepts: every scheme a gateway
 * route can point at, not only the ones the JDK can open a connection to.
 */
class RouteCreateModelTests {

	private static final List<PredicateCreateModel> PREDICATES = List
		.of(new PredicateCreateModel("Method", Map.of("methods", "GET")));

	@ParameterizedTest
	@ValueSource(strings = { "lb://service-a", "ws://service-a:8080/socket", "wss://service-a/socket",
			"forward:/fallback", "http://service-a", "https://service-a:8443/base" })
	void shouldAcceptEverySchemeARouteCanTarget(String uri) {
		assertThatCode(() -> new RouteCreateModel("route", URI.create(uri), null, PREDICATES, null))
			.doesNotThrowAnyException();
	}

	@Test
	void shouldKeepTheTargetAsItWasGiven() {
		RouteCreateModel model = new RouteCreateModel("route", URI.create("lb://service-a"), null, PREDICATES, null);

		assertThat(model.uri()).isEqualTo(URI.create("lb://service-a"));
	}

	@Test
	void shouldRejectATargetWithoutScheme() {
		assertThatThrownBy(() -> new RouteCreateModel("route", URI.create("service-a/path"), null, PREDICATES, null))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void shouldLeaveAMissingTargetToTheValidation() {
		assertThatCode(() -> new RouteCreateModel("route", null, null, PREDICATES, null)).doesNotThrowAnyException();
	}

}
