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

package ch.nexsol.gateway.commons.security;

import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceCheckingJwtDecoderTests {

	private final AudienceCheckingJwtDecoder decoder = new AudienceCheckingJwtDecoder((token) -> Mono.just(jwt(token)),
			List.of("https://gateway/mcp", "console"));

	@Test
	void acceptsATokenCarryingOneOfTheAudiences() {
		StepVerifier.create(this.decoder.decode("console,other"))
			.assertNext((jwt) -> assertThat(jwt.getAudience()).contains("console"))
			.verifyComplete();
	}

	@Test
	void rejectsATokenIssuedForAnotherResource() {
		StepVerifier.create(this.decoder.decode("other"))
			.expectErrorSatisfies((error) -> assertThat(error).isInstanceOf(JwtValidationException.class)
				.hasMessage("The token was not issued for this gateway"))
			.verify();
	}

	@Test
	void rejectsATokenWithoutAnAudience() {
		StepVerifier.create(this.decoder.decode("")).expectError(JwtValidationException.class).verify();
	}

	@Test
	void passesTheFailuresOfTheIssuerThrough() {
		AudienceCheckingJwtDecoder failing = new AudienceCheckingJwtDecoder(
				(token) -> Mono.error(new JwtException("bad signature")), List.of("console"));
		StepVerifier.create(failing.decode("x"))
			.expectErrorSatisfies((error) -> assertThat(error).isInstanceOf(JwtException.class)
				.isNotInstanceOf(JwtValidationException.class))
			.verify();
	}

	private static Jwt jwt(String audiences) {
		Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none").subject("alice");
		if (!audiences.isEmpty()) {
			builder.audience(List.of(audiences.split(",")));
		}
		return builder.build();
	}

}
