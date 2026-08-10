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

package ch.nexsol.gateway.validation.factory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.validation.OpenapiContractRegistry;
import ch.nexsol.gateway.validation.OpenapiValidationProperties;
import ch.nexsol.gateway.validation.ValidationAttributes;
import ch.nexsol.gateway.validation.ValidationMetrics;
import ch.nexsol.gateway.validation.ValidationMode;
import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory.Config;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.NameUtils;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the filter forwards, what it rejects and what it deliberately leaves
 * alone, in both modes and in both directions.
 */
class OpenapiValidationGatewayFilterFactoryTests {

	private static final String CONTRACT = "classpath:openapi/bookstore.yaml";

	private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final OpenapiValidationProperties properties = new OpenapiValidationProperties();

	private final AtomicBoolean forwarded = new AtomicBoolean();

	private final AtomicInteger forwards = new AtomicInteger();

	private final GatewayFilterChain forwardingChain = (exchange) -> {
		this.forwarded.set(true);
		this.forwards.incrementAndGet();
		return Mono.empty();
	};

	@Test
	void forwardsTheExchangeExactlyOnce() {
		// A filter returns Mono<Void>, which completes empty: any 'switch if empty' in
		// the
		// filter would run the rest of the chain a second time, sending every request
		// twice.
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books?page=1"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwards).hasValue(1);
	}

	@Test
	void forwardsTheExchangeExactlyOnceWhenTheContractCannotBeRead() {
		GatewayFilter filter = filter("classpath:openapi/does-not-exist.yaml");
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwards).hasValue(1);
	}

	@Test
	void forwardsARequestThatHonoursTheContract() {
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books?page=1"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.REQUEST_VALID, "true")
			.containsEntry(ValidationAttributes.OPERATION, "GET /books");
	}

	@Test
	void rejectsARequestThatBreaksTheContractWhenEnforced() {
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books?page=first"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyErrorSatisfies((error) -> {
			assertThat(error).isInstanceOf(ResponseStatusException.class);
			assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		});

		assertThat(this.forwarded).isFalse();
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.REQUEST_VALID, "false");
		assertThat(counter("request", "invalid")).isEqualTo(1);
	}

	@Test
	void forwardsARequestThatBreaksTheContractWhenOnlyReporting() {
		this.properties.getRequest().setMode(ValidationMode.REPORT);
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books?page=first"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.REQUEST_VALID, "false")
			.hasEntrySatisfying(ValidationAttributes.REQUEST_ERRORS, (errors) -> assertThat(errors).contains("page"));
	}

	@Test
	void rejectsARequestOnAPathTheContractDoesNotDeclare() {
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/authors"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain))
			.verifyErrorSatisfies((error) -> assertThat(((ResponseStatusException) error).getReason())
				.contains("declares no operation matching '/authors'"));

		assertThat(this.forwarded).isFalse();
	}

	@Test
	void rejectsARequestWhoseBodyBreaksTheContract() {
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(jsonPost("/books", "{\"title\":\"Dune\"}"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain))
			.verifyErrorSatisfies(
					(error) -> assertThat(((ResponseStatusException) error).getReason()).contains("author"));

		assertThat(this.forwarded).isFalse();
	}

	@Test
	void forwardsARequestWhoseBodyHonoursTheContract() {
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(jsonPost("/books", "{\"title\":\"Dune\",\"author\":\"Herbert\"}"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.REQUEST_VALID, "true");
		// The body really was read: no skip was recorded for this exchange.
		assertThat(this.meterRegistry.find(ValidationMetrics.BODIES_SKIPPED).counter()).isNull();
	}

	@Test
	void neverReadsAnUploadIntoMemory() {
		GatewayFilter filter = filter();
		String payload = "--boundary--binary payload--boundary--";
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/books/7/cover")
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.contentLength(payload.length())
			.body(payload));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.REQUEST_VALID, "true");
		// The payload was skipped on its media type alone, before anything was buffered.
		assertThat(skipped("request", "not_json")).isEqualTo(1);
	}

	@Test
	void doesNotReadABodyLargerThanTheConfiguredMaximum() {
		this.properties.getRequest().setMaxBodySize(DataSize.ofBytes(4));
		GatewayFilter filter = filter();
		// A body that would be rejected if it were read: it is forwarded instead.
		MockServerWebExchange exchange = exchange(jsonPost("/books", "{\"title\":\"Dune\"}"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(skipped("request", "too_large")).isEqualTo(1);
	}

	@Test
	void doesNotReadABodyWhoseLengthIsNotAnnounced() {
		GatewayFilter filter = filter();
		// No Content-Length, so buffering could not be bounded: the body is forwarded
		// unread even though it breaks the contract.
		MockServerWebExchange exchange = exchange(
				MockServerHttpRequest.post("/books").contentType(MediaType.APPLICATION_JSON).body("{\"title\":\"D\"}"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(skipped("request", "unknown_length")).isEqualTo(1);
	}

	@Test
	void forwardsAResponseThatBreaksTheContractWhenOnlyReporting() {
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books/7"));
		String upstreamBody = "{\"title\":\"Dune\",\"pages\":0}";

		StepVerifier.create(filter.filter(exchange, respondingChain(HttpStatus.OK, upstreamBody))).verifyComplete();

		MockServerHttpResponse response = exchange.getResponse();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBodyAsString().block()).isEqualTo(upstreamBody);
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.RESPONSE_VALID, "false");
	}

	@Test
	void replacesAResponseThatBreaksTheContractWhenEnforced() {
		this.properties.getResponse().setMode(ValidationMode.ENFORCE);
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books/7"));

		StepVerifier.create(filter.filter(exchange, respondingChain(HttpStatus.OK, "{\"title\":\"Dune\",\"pages\":0}")))
			.verifyComplete();

		MockServerHttpResponse response = exchange.getResponse();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(response.getBodyAsString().block()).contains("does not honour its OpenAPI contract")
			.contains("GET /books/{id}");
	}

	@Test
	void forwardsAResponseThatHonoursTheContract() {
		this.properties.getResponse().setMode(ValidationMode.ENFORCE);
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books/7"));
		String upstreamBody = "{\"title\":\"Dune\",\"author\":\"Herbert\"}";

		StepVerifier.create(filter.filter(exchange, respondingChain(HttpStatus.OK, upstreamBody))).verifyComplete();

		MockServerHttpResponse response = exchange.getResponse();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBodyAsString().block()).isEqualTo(upstreamBody);
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.RESPONSE_VALID, "true");
	}

	@Test
	void leavesTheResponseAloneWhenTheResponseDirectionIsOff() {
		this.properties.getResponse().setMode(ValidationMode.OFF);
		GatewayFilter filter = filter();
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books/7"));

		StepVerifier.create(filter.filter(exchange, respondingChain(HttpStatus.I_AM_A_TEAPOT, "{}"))).verifyComplete();

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.I_AM_A_TEAPOT);
		assertThat(validationAttributes(exchange)).doesNotContainKey(ValidationAttributes.RESPONSE_VALID);
	}

	@Test
	void forwardsUnvalidatedWhenTheContractCannotBeRead() {
		GatewayFilter filter = filter("classpath:openapi/does-not-exist.yaml");
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/books?page=first"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.REQUEST_VALID, "false")
			.hasEntrySatisfying(ValidationAttributes.REQUEST_ERRORS,
					(errors) -> assertThat(errors).contains("could not be read"));
		assertThat(this.meterRegistry.find(ValidationMetrics.CONTRACTS_UNAVAILABLE).counter().count()).isEqualTo(1);
	}

	@Test
	void stripsTheConfiguredPrefixBeforeMatchingTheContract() {
		GatewayFilter filter = filter(CONTRACT, "/book-service");
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/book-service/books?page=1"));

		StepVerifier.create(filter.filter(exchange, this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
		assertThat(validationAttributes(exchange)).containsEntry(ValidationAttributes.OPERATION, "GET /books");
	}

	@Test
	void mapsTheShortcutArgumentsToTheContractAndThePrefix() {
		OpenapiValidationGatewayFilterFactory factory = factory();

		assertThat(factory.shortcutFieldOrder()).containsExactly("specUrl", "pathPrefix");
	}

	/**
	 * A contract is very often named by an {@code http(s)} URL, which carries a {@code :}
	 * and slashes. This checks the documented shortcut really binds it, rather than
	 * mangling it or reading it as an expression.
	 */
	@Test
	void bindsAUrlContractThroughTheShortcutForm() {
		OpenapiValidationGatewayFilterFactory factory = factory();
		Map<String, String> shortcutArgs = new LinkedHashMap<>();
		shortcutArgs.put(NameUtils.GENERATED_NAME_PREFIX + "0", "https://petstore3.swagger.io/api/v3/openapi.json");
		shortcutArgs.put(NameUtils.GENERATED_NAME_PREFIX + "1", "/book-service");

		Map<String, Object> normalized = factory.shortcutType()
			.normalize(shortcutArgs, factory, new SpelExpressionParser(), new DefaultListableBeanFactory());

		assertThat(normalized).containsEntry("specUrl", "https://petstore3.swagger.io/api/v3/openapi.json")
			.containsEntry("pathPrefix", "/book-service");
	}

	@Test
	void bindsAContractGivenWithoutAPrefixThroughTheShortcutForm() {
		OpenapiValidationGatewayFilterFactory factory = factory();
		Map<String, String> shortcutArgs = new LinkedHashMap<>();
		shortcutArgs.put(NameUtils.GENERATED_NAME_PREFIX + "0", CONTRACT);

		Map<String, Object> normalized = factory.shortcutType()
			.normalize(shortcutArgs, factory, new SpelExpressionParser(), new DefaultListableBeanFactory());

		assertThat(normalized).containsOnlyKeys("specUrl").containsEntry("specUrl", CONTRACT);
	}

	private OpenapiValidationGatewayFilterFactory factory() {
		return new OpenapiValidationGatewayFilterFactory(new OpenapiContractRegistry(new DefaultResourceLoader()),
				this.properties, new ValidationMetrics(this.meterRegistry));
	}

	private GatewayFilter filter() {
		return filter(CONTRACT);
	}

	private GatewayFilter filter(String contract) {
		return filter(contract, null);
	}

	private GatewayFilter filter(String contract, String pathPrefix) {
		OpenapiValidationGatewayFilterFactory factory = new OpenapiValidationGatewayFilterFactory(
				new OpenapiContractRegistry(new DefaultResourceLoader()), this.properties,
				new ValidationMetrics(this.meterRegistry));
		return factory.apply(new Config().setSpecUrl(contract).setPathPrefix(pathPrefix));
	}

	/**
	 * A chain standing in for the upstream service: it answers with the given status and
	 * body, through whatever response the filter decorated the exchange with.
	 */
	private GatewayFilterChain respondingChain(HttpStatus status, String body) {
		return (exchange) -> {
			this.forwarded.set(true);
			exchange.getResponse().setStatusCode(status);
			exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponse().getHeaders().setContentLength(bytes.length);
			return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
		};
	}

	private static MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
		return MockServerWebExchange.from(request.build());
	}

	private static MockServerWebExchange exchange(MockServerHttpRequest request) {
		return MockServerWebExchange.from(request);
	}

	/**
	 * A JSON request announcing its length, which is what the filter requires before it
	 * will read a body at all.
	 */
	private static MockServerHttpRequest jsonPost(String path, String body) {
		return MockServerHttpRequest.post(path)
			.contentType(MediaType.APPLICATION_JSON)
			.contentLength(body.getBytes(StandardCharsets.UTF_8).length)
			.body(body);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> validationAttributes(ServerWebExchange exchange) {
		Object attributes = exchange.getAttribute(ValidationAttributes.VALIDATION_ATTRIBUTES_ATTR);
		return (attributes != null) ? (Map<String, String>) attributes : Map.of();
	}

	private double counter(String direction, String outcome) {
		return this.meterRegistry.find(ValidationMetrics.VALIDATIONS)
			.tag("direction", direction)
			.tag("outcome", outcome)
			.counter()
			.count();
	}

	private double skipped(String direction, String reason) {
		return this.meterRegistry.find(ValidationMetrics.BODIES_SKIPPED)
			.tag("direction", direction)
			.tag("reason", reason)
			.counter()
			.count();
	}

}
