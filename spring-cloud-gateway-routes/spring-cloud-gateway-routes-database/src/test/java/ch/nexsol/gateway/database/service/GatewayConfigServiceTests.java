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

package ch.nexsol.gateway.database.service;

import java.util.ArrayList;
import java.util.Map;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AddRequestHeaderGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.MethodRoutePredicateFactory;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GatewayConfigServiceTests {

	@Autowired
	GatewayConfigService gatewayConfigService;

	@Test
	void shouldHaveSCGFilters() {
		StepVerifier.create(this.gatewayConfigService.getAvailableFilters())
			.recordWith(ArrayList::new)
			.thenConsumeWhile((x) -> true)
			.expectRecordedMatches((elements) -> !elements.isEmpty())
			.verifyComplete();

	}

	@Test
	void shouldHaveSCGPredicates() {
		StepVerifier.create(this.gatewayConfigService.getAvailablePredicates())
			.recordWith(ArrayList::new)
			.thenConsumeWhile((x) -> true)
			.expectRecordedMatches((elements) -> !elements.isEmpty())
			.verifyComplete();

	}

	@Test
	void shouldFilterArgsValidationSuccess() {
		AddRequestHeaderGatewayFilterFactory factory = new AddRequestHeaderGatewayFilterFactory();
		StepVerifier
			.create(this.gatewayConfigService.validateFilter(factory.name(),
					Map.of(GatewayFilter.NAME_KEY, "X-Request-red", GatewayFilter.VALUE_KEY, "blue")))
			.assertNext((b) -> assertThat(b).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldFilterArgsValidationFailed() {
		AddRequestHeaderGatewayFilterFactory factory = new AddRequestHeaderGatewayFilterFactory();
		StepVerifier
			.create(this.gatewayConfigService.validateFilter(factory.name(),
					Map.of(GatewayFilter.NAME_KEY, "X-Request-red")))
			.assertNext((b) -> assertThat(b).isFalse())
			.verifyComplete();
		StepVerifier
			.create(this.gatewayConfigService.validateFilter(factory.name(), Map.of("_unknown_", "X-Request-red")))
			.assertNext((b) -> assertThat(b).isFalse())
			.verifyComplete();
		StepVerifier.create(this.gatewayConfigService.validateFilter(factory.name(), Map.of()))
			.assertNext((b) -> assertThat(b).isFalse())
			.verifyComplete();
	}

	@Test
	void shouldExposePredicateArgumentDefaults() {
		Map<String, String> defaults = this.gatewayConfigService.getDefaultArgsForPredicate("Path");
		assertThat(defaults).containsKeys("patterns", "matchTrailingSlash");
		assertThat(defaults.get("matchTrailingSlash")).isEqualTo("true");
		assertThat(defaults.get("patterns")).isEmpty();
	}

	@Test
	void shouldExposeFilterArgumentDefaults() {
		Map<String, String> defaults = this.gatewayConfigService.getDefaultArgsForFilter("StripPrefix");
		assertThat(defaults).containsEntry("parts", "1");
	}

	@Test
	void shouldFilterArgsValidationSuccessWithTypedArgument() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("StripPrefix", Map.of("parts", "2")))
			.assertNext((b) -> assertThat(b).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldFilterArgsValidationFailedOnTypeMismatch() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("StripPrefix", Map.of("parts", "notanint")))
			.assertNext((b) -> assertThat(b).isFalse())
			.verifyComplete();
	}

	@Test
	void shouldAcceptFilterWithoutFillingEveryOptionalArg() {
		// Retry exposes several fields but none is strictly required (no bean validation
		// constraint): providing a single one must be enough, the rest keep their
		// defaults.
		Map<String, String> accepted = this.gatewayConfigService.getDefaultArgsForFilter("Retry");
		assertThat(accepted).hasSizeGreaterThan(1);
		assertThat(accepted).containsKey("retries");

		StepVerifier.create(this.gatewayConfigService.validateFilter("Retry", Map.of("retries", "2")))
			.assertNext((b) -> assertThat(b).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldRejectFilterWithUnknownArg() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("Retry", Map.of("_unknown_", "2")))
			.assertNext((b) -> assertThat(b).isFalse())
			.verifyComplete();
	}

	@Test
	void shouldPredicateArgsValidationSuccess() {
		MethodRoutePredicateFactory factory = new MethodRoutePredicateFactory();
		StepVerifier
			.create(this.gatewayConfigService.validatePredicate(factory.name(),
					Map.of(MethodRoutePredicateFactory.METHODS_KEY, "GET")))
			.assertNext((b) -> assertThat(b).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldPredicateArgsValidationFailed() {
		MethodRoutePredicateFactory factory = new MethodRoutePredicateFactory();
		StepVerifier.create(this.gatewayConfigService.validatePredicate(factory.name(), Map.of("_unknown_", "GET")))
			.assertNext((b) -> assertThat(b).isFalse())
			.verifyComplete();
		StepVerifier.create(this.gatewayConfigService.validatePredicate(factory.name(), Map.of()))
			.assertNext((b) -> assertThat(b).isFalse())
			.verifyComplete();
	}

	@Test
	void shouldPredicateArgsValidationSuccessWithBoolean() {
		StepVerifier
			.create(this.gatewayConfigService.validatePredicate("Path",
					Map.of("patterns", "/x/**", "matchTrailingSlash", "true")))
			.assertNext((b) -> assertThat(b).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldPredicateArgsValidationFailedOnTypeMismatch() {
		StepVerifier
			.create(this.gatewayConfigService.validatePredicate("Path",
					Map.of("patterns", "/x/**", "matchTrailingSlash", "sadfsdf")))
			.expectError(PredicateArgsFormatException.class)
			.verify();
	}

}
