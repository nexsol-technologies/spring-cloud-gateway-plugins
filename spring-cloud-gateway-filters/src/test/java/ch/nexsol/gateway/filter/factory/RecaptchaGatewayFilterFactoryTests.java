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

package ch.nexsol.gateway.filter.factory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.nexsol.gateway.filter.factory.RecaptchaGatewayFilterFactory.Config;
import ch.nexsol.gateway.filter.factory.RecaptchaGatewayFilterFactory.Version;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the reCAPTCHA filter forwards and what it denies, against a stubbed
 * verification endpoint. Every rejection is expected to carry {@code 403}: a refusal is
 * not a failure of the gateway, and it must not read as one.
 */
class RecaptchaGatewayFilterFactoryTests {

	private static final String VERIFY_URL = "https://recaptcha.example.org/siteverify";

	private final AtomicBoolean forwarded = new AtomicBoolean();

	private final GatewayFilterChain chain = (exchange) -> {
		this.forwarded.set(true);
		return Mono.empty();
	};

	@Test
	void shouldForwardAV3TokenScoringAboveTheThreshold() {
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 0.9, "hostname": "example.org"}""", config(Version.V3, (short) 50));

		StepVerifier.create(filter.filter(request("token"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	@Test
	void shouldDenyAV3TokenScoringBelowTheThreshold() {
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 0.2, "hostname": "example.org"}""", config(Version.V3, (short) 50));

		expectForbidden(filter);
	}

	/**
	 * The score is compared on the 0.0-1.0 scale the provider answers on. Scaling it up
	 * instead rejects this very case: {@code 0.29 * 100} is 28.999999999999996.
	 */
	@Test
	void shouldAcceptAV3ScoreExactlyMeetingTheThreshold() {
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 0.29, "hostname": "example.org"}""", config(Version.V3, (short) 29));

		StepVerifier.create(filter.filter(request("token"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	@Test
	void shouldDenyAV3TokenTheProviderRejected() {
		GatewayFilter filter = filterAnswering("""
				{"success": false, "error-codes": ["invalid-input-response"]}""", config(Version.V3, (short) 50));

		expectForbidden(filter);
	}

	@Test
	void shouldForwardAValidV2Token() {
		GatewayFilter filter = filterAnswering("""
				{"success": true, "hostname": "example.org"}""", config(Version.V2, (short) 50));

		StepVerifier.create(filter.filter(request("token"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	/**
	 * A v2 answer carries no score, so the threshold must play no part in the verdict:
	 * the version drives the validation, not the shape of the payload.
	 */
	@Test
	void shouldIgnoreTheScoreThresholdInV2() {
		GatewayFilter filter = filterAnswering("""
				{"success": true, "hostname": "example.org"}""", config(Version.V2, (short) 100));

		StepVerifier.create(filter.filter(request("token"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	@Test
	void shouldDenyAV2TokenTheProviderRejected() {
		GatewayFilter filter = filterAnswering("""
				{"success": false}""", config(Version.V2, (short) 50));

		expectForbidden(filter);
	}

	@Test
	void shouldDenyARequestCarryingNoToken() {
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 1.0}""", config(Version.V3, (short) 50));

		StepVerifier
			.create(filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("https://gateway/api")),
					this.chain))
			.verifyErrorSatisfies(
					(error) -> assertThat(asStatusException(error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThat(this.forwarded).isFalse();
	}

	@Test
	void shouldDenyARequestCarryingABlankToken() {
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 1.0}""", config(Version.V3, (short) 50));

		StepVerifier
			.create(filter.filter(MockServerWebExchange
				.from(MockServerHttpRequest.get("https://gateway/api").header("recaptcha", "  ")), this.chain))
			.verifyErrorSatisfies(
					(error) -> assertThat(asStatusException(error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThat(this.forwarded).isFalse();
	}

	/**
	 * Fails closed: an answer the filter cannot read a verdict from denies the request
	 * rather than letting it through unverified.
	 */
	@Test
	void shouldDenyWhenTheProviderAnswersAnEmptyBody() {
		GatewayFilter filter = filterAnswering("", config(Version.V3, (short) 50));

		expectForbidden(filter);
	}

	@Test
	void shouldDenyWhenTheProviderIsUnreachable() {
		WebClient webClient = WebClient.builder()
			.exchangeFunction((request) -> Mono.error(new IllegalStateException("connection refused")))
			.build();
		GatewayFilter filter = new RecaptchaGatewayFilterFactory(webClient).apply(config(Version.V3, (short) 50));

		expectForbidden(filter);
	}

	/**
	 * A verification endpoint answering an error of its own is a problem with the gateway
	 * configuration or with Google, never with the caller: the request is denied like any
	 * other unverified one, and the real status is logged rather than handed back.
	 */
	@Test
	void shouldDenyWithoutRelayingTheProviderStatusWhenTheProviderFails() {
		expectForbidden(
				filterAnswering(HttpStatus.INTERNAL_SERVER_ERROR, "upstream exploded", config(Version.V3, (short) 50)));
	}

	@Test
	void shouldDenyWhenTheProviderRefusesTheSecretKey() {
		expectForbidden(filterAnswering(HttpStatus.BAD_REQUEST, "", config(Version.V3, (short) 50)));
	}

	@Test
	void shouldDenyWhenTheProviderAnswersUnreadableJson() {
		expectForbidden(filterAnswering("not json at all", config(Version.V3, (short) 50)));
	}

	/**
	 * The scheme guard skips the filter, so it must not answer a scheme it was meant to
	 * cover. The comparison is case-insensitive on both schemes, not only on
	 * {@code http}.
	 */
	@Test
	void shouldVerifyWhateverTheCaseOfTheScheme() {
		GatewayFilter filter = filterAnswering("""
				{"success": false}""", config(Version.V3, (short) 50));

		StepVerifier
			.create(filter.filter(MockServerWebExchange
				.from(MockServerHttpRequest.get("HTTPS://gateway/api").header("recaptcha", "token")), this.chain))
			.verifyErrorSatisfies(
					(error) -> assertThat(asStatusException(error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThat(this.forwarded).isFalse();
	}

	@Test
	void shouldDefaultToTheScoreGoogleRecommends() {
		assertThat(new Config().getScore()).isEqualTo((short) 50);
	}

	@Test
	void shouldDefaultToV3AndToTheRecaptchaHeader() {
		assertThat(new Config().getVersion()).isEqualTo(Version.V3);
		assertThat(new Config().getRecaptchaHttpHeader()).isEqualTo("recaptcha");
	}

	@Test
	void shouldMapTheShortcutArgumentsInOrder() {
		assertThat(new RecaptchaGatewayFilterFactory(WebClient.create()).shortcutFieldOrder())
			.containsExactly("verifyUrl", "version", "secretKey", "recaptchaHttpHeader", "score");
	}

	@Test
	void shouldRejectAV3TokenSolvedForAnotherAction() {
		// A token obtained on a harmless action of the site is otherwise replayable
		// against the action this route protects.
		Config config = config(Version.V3, (short) 50);
		config.setAction("checkout");
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 0.9, "action": "page_view", "hostname": "example.org"}""", config);

		expectForbidden(filter);
	}

	@Test
	void shouldAcceptAV3TokenSolvedForTheExpectedAction() {
		Config config = config(Version.V3, (short) 50);
		config.setAction("checkout");
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 0.9, "action": "checkout", "hostname": "example.org"}""", config);

		StepVerifier.create(filter.filter(request("token"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	@Test
	void shouldAcceptAnyActionWhenNoneIsConfigured() {
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 0.9, "action": "whatever", "hostname": "example.org"}""",
				config(Version.V3, (short) 50));

		StepVerifier.create(filter.filter(request("token"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	@Test
	void shouldRejectAChallengeSolvedOnAnotherHostname() {
		// Google verifies the token, not its origin: a token solved on any other site
		// registered under the same secret would otherwise be accepted here.
		Config config = config(Version.V3, (short) 50);
		config.setHostnames(List.of("example.org"));
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 0.9, "hostname": "attacker.example.com"}""", config);

		expectForbidden(filter);
	}

	@Test
	void shouldRejectAV2ChallengeSolvedOnAnotherHostname() {
		Config config = config(Version.V2, (short) 50);
		config.setHostnames(List.of("example.org"));
		GatewayFilter filter = filterAnswering("""
				{"success": true, "hostname": "attacker.example.com"}""", config);

		expectForbidden(filter);
	}

	@Test
	void shouldAcceptAChallengeSolvedOnAnExpectedHostname() {
		Config config = config(Version.V3, (short) 50);
		config.setHostnames(List.of("other.example.org", "example.org"));
		GatewayFilter filter = filterAnswering("""
				{"success": true, "score": 0.9, "hostname": "example.org"}""", config);

		StepVerifier.create(filter.filter(request("token"), this.chain)).verifyComplete();
		assertThat(this.forwarded).isTrue();
	}

	@Test
	void shouldNotCheckTheOriginWhenNothingIsConfigured() {
		assertThat(new Config().getAction()).isNull();
		assertThat(new Config().getHostnames()).isEmpty();
	}

	private void expectForbidden(GatewayFilter filter) {
		StepVerifier.create(filter.filter(request("token"), this.chain))
			.verifyErrorSatisfies(
					(error) -> assertThat(asStatusException(error).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThat(this.forwarded).isFalse();
	}

	private ResponseStatusException asStatusException(Throwable error) {
		assertThat(error).isInstanceOf(ResponseStatusException.class);
		return (ResponseStatusException) error;
	}

	private GatewayFilter filterAnswering(String body, Config config) {
		return filterAnswering(HttpStatus.OK, body, config);
	}

	private GatewayFilter filterAnswering(HttpStatus status, String body, Config config) {
		WebClient webClient = WebClient.builder()
			.exchangeFunction((request) -> Mono.just(ClientResponse.create(status)
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.body(body)
				.build()))
			.build();
		return new RecaptchaGatewayFilterFactory(webClient).apply(config);
	}

	private Config config(Version version, short score) {
		Config config = new Config();
		config.setVerifyUrl(VERIFY_URL);
		config.setSecretKey("secret");
		config.setVersion(version);
		config.setScore(score);
		return config;
	}

	private ServerWebExchange request(String token) {
		return MockServerWebExchange
			.from(MockServerHttpRequest.get(URI.create("https://gateway/api").toString()).header("recaptcha", token));
	}

}
