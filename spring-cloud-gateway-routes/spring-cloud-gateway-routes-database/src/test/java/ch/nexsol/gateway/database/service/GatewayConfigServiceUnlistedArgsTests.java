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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the validation of arguments a filter takes outside its shortcut notation: a
 * factory may declare no shortcut at all, or fewer names than its configuration holds.
 */
@SpringBootTest
class GatewayConfigServiceUnlistedArgsTests {

	@Autowired
	GatewayConfigService gatewayConfigService;

	@Test
	void shouldAcceptAConfigurationFieldOfAFilterWithoutShortcut() {
		StepVerifier
			.create(this.gatewayConfigService.validateFilter("Window",
					Map.of("start", "2026-01-01T08:00:00+01:00", "end", "2026-01-01T10:00:00+01:00")))
			.assertNext((valid) -> assertThat(valid).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldAcceptAConfigurationFieldLeftOutOfTheShortcut() {
		StepVerifier
			.create(this.gatewayConfigService.validateFilter("Labelled", Map.of("label", "blue", "comment", "x")))
			.assertNext((valid) -> assertThat(valid).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldAcceptANestedConfigurationField() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("Window", Map.of("claims[0].path", "$.role")))
			.assertNext((valid) -> assertThat(valid).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldStillAcceptAFilterWithoutShortcutAndWithoutArguments() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("Window", Map.of()))
			.assertNext((valid) -> assertThat(valid).isTrue())
			.verifyComplete();
	}

	@Test
	void shouldStillRejectANameTheConfigurationDoesNotHold() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("Window", Map.of("_unknown_", "x")))
			.assertNext((valid) -> assertThat(valid).isFalse())
			.verifyComplete();
		StepVerifier
			.create(this.gatewayConfigService.validateFilter("Labelled", Map.of("label", "blue", "_unknown_", "x")))
			.assertNext((valid) -> assertThat(valid).isFalse())
			.verifyComplete();
	}

	@Test
	void shouldNotTakeAPropertyOfEveryObjectForAnArgument() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("Window", Map.of("class", "java.lang.String")))
			.assertNext((valid) -> assertThat(valid).isFalse())
			.verifyComplete();
	}

	@Test
	void shouldRejectAValueTheConfigurationFieldCannotHold() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("Window", Map.of("start", "not-a-date")))
			.assertNext((valid) -> assertThat(valid).isFalse())
			.verifyComplete();
	}

	@Test
	void shouldStillRequireTheRequiredShortcutArgument() {
		StepVerifier.create(this.gatewayConfigService.validateFilter("Labelled", Map.of("comment", "x")))
			.assertNext((valid) -> assertThat(valid).isFalse())
			.verifyComplete();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Factories {

		@Bean
		WindowGatewayFilterFactory windowGatewayFilterFactory() {
			return new WindowGatewayFilterFactory();
		}

		@Bean
		LabelledGatewayFilterFactory labelledGatewayFilterFactory() {
			return new LabelledGatewayFilterFactory();
		}

	}

	/**
	 * A filter configured by name only, the way a maintenance window is: it declares no
	 * shortcut field.
	 */
	static class WindowGatewayFilterFactory extends AbstractGatewayFilterFactory<WindowGatewayFilterFactory.Config> {

		WindowGatewayFilterFactory() {
			super(Config.class);
		}

		@Override
		public GatewayFilter apply(Config config) {
			return (exchange, chain) -> chain.filter(exchange);
		}

		public static class Config {

			private OffsetDateTime start;

			private OffsetDateTime end;

			private List<Claim> claims = new ArrayList<>();

			public OffsetDateTime getStart() {
				return this.start;
			}

			public void setStart(OffsetDateTime start) {
				this.start = start;
			}

			public OffsetDateTime getEnd() {
				return this.end;
			}

			public void setEnd(OffsetDateTime end) {
				this.end = end;
			}

			public List<Claim> getClaims() {
				return this.claims;
			}

			public void setClaims(List<Claim> claims) {
				this.claims = claims;
			}

		}

		public static class Claim {

			private String path;

			public String getPath() {
				return this.path;
			}

			public void setPath(String path) {
				this.path = path;
			}

		}

	}

	/**
	 * A filter whose shortcut names one field out of the two its configuration holds.
	 */
	static class LabelledGatewayFilterFactory
			extends AbstractGatewayFilterFactory<LabelledGatewayFilterFactory.Config> {

		LabelledGatewayFilterFactory() {
			super(Config.class);
		}

		@Override
		public List<String> shortcutFieldOrder() {
			return List.of("label");
		}

		@Override
		public GatewayFilter apply(Config config) {
			return (exchange, chain) -> chain.filter(exchange);
		}

		public static class Config {

			@NotEmpty
			private String label;

			private String comment;

			public String getLabel() {
				return this.label;
			}

			public void setLabel(String label) {
				this.label = label;
			}

			public String getComment() {
				return this.comment;
			}

			public void setComment(String comment) {
				this.comment = comment;
			}

		}

	}

}
