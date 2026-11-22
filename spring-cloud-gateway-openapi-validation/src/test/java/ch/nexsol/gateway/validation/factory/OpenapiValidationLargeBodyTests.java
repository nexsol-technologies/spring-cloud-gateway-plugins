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
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.validation.OpenapiContractRegistry;
import ch.nexsol.gateway.validation.OpenapiValidationProperties;
import ch.nexsol.gateway.validation.ValidationAttributes;
import ch.nexsol.gateway.validation.ValidationMetrics;
import ch.nexsol.gateway.validation.ValidationMode;
import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory.Config;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies where a large body is validated. Parsing a megabyte of JSON and holding it
 * against a schema takes milliseconds, during which the thread doing it serves nobody
 * else: past a size, that work leaves the thread the gateway routes on. A small body is
 * validated where it arrives, a hop to another thread costing more than it saves.
 */
class OpenapiValidationLargeBodyTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final OpenapiValidationProperties properties = new OpenapiValidationProperties();

	private final AtomicReference<String> forwardedOn = new AtomicReference<>();

	private final GatewayFilterChain forwardingChain = (exchange) -> {
		this.forwardedOn.set(Thread.currentThread().getName());
		return Mono.empty();
	};

	@Test
	void validatesALargeRequestBodyAwayFromTheThreadItArrivedOn() {
		MockServerWebExchange exchange = exchange(jsonPost(items(4000)));

		StepVerifier.create(filter().filter(exchange, this.forwardingChain)).expectComplete().verify(TIMEOUT);

		assertThat(this.forwardedOn.get()).startsWith("boundedElastic");
		assertThat(attribute(exchange, ValidationAttributes.REQUEST_VALID)).isEqualTo("true");
	}

	@Test
	void validatesASmallRequestBodyOnTheThreadItArrivedOn() {
		MockServerWebExchange exchange = exchange(jsonPost(items(3)));

		StepVerifier.create(filter().filter(exchange, this.forwardingChain)).expectComplete().verify(TIMEOUT);

		assertThat(this.forwardedOn.get()).isEqualTo(Thread.currentThread().getName());
		assertThat(attribute(exchange, ValidationAttributes.REQUEST_VALID)).isEqualTo("true");
	}

	@Test
	void stillRejectsALargeRequestBodyThatBreaksTheContract() {
		String body = "[" + String.join(",", Collections.nCopies(4000, "{\"name\":\"pen\",\"price\":1.25}"))
				+ ",{\"price\":1.25}]";
		MockServerWebExchange exchange = exchange(jsonPost(body));

		StepVerifier.create(filter().filter(exchange, this.forwardingChain)).expectErrorSatisfies((error) -> {
			assertThat(error).isInstanceOf(ResponseStatusException.class);
			assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		}).verify(TIMEOUT);

		assertThat(this.forwardedOn.get()).isNull();
	}

	@Test
	void validatesALargeResponseBodyAwayFromTheThreadItArrivedOn() {
		this.properties.getResponse().setMode(ValidationMode.ENFORCE);
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/catalog").build());
		AtomicReference<String> committedOn = new AtomicReference<>();
		exchange.getResponse().beforeCommit(() -> {
			committedOn.set(Thread.currentThread().getName());
			return Mono.empty();
		});
		String body = items(4000);

		StepVerifier.create(filter().filter(exchange, responding(body))).expectComplete().verify(TIMEOUT);

		assertThat(committedOn.get()).startsWith("boundedElastic");
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo(body);
	}

	@Test
	void stillReplacesALargeResponseBodyThatBreaksTheContract() {
		this.properties.getResponse().setMode(ValidationMode.ENFORCE);
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/catalog").build());
		String body = "[" + String.join(",", Collections.nCopies(4000, "{\"name\":\"pen\"}")) + ",{\"price\":1}]";

		StepVerifier.create(filter().filter(exchange, responding(body))).expectComplete().verify(TIMEOUT);

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
	}

	@Test
	void refusesABodyLargerThanTheMaximumItAnnouncedItselfWithin() {
		this.properties.getRequest().setMaxBodySize(DataSize.ofBytes(64));
		String body = items(10);
		MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/catalog")
			.contentType(MediaType.APPLICATION_JSON)
			.contentLength(10)
			.body(body));

		StepVerifier.create(filter().filter(exchange, this.forwardingChain)).expectErrorSatisfies((error) -> {
			assertThat(error).isInstanceOf(ResponseStatusException.class);
			assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
		}).verify(TIMEOUT);

		assertThat(this.forwardedOn.get()).isNull();
	}

	@Test
	void keepsNoHoldOnTheBodyItReadWhateverItsSize() {
		for (int count : new int[] { 3, 4000 }) {
			byte[] body = items(count).getBytes(StandardCharsets.UTF_8);
			ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
			NettyDataBuffer buffer = new NettyDataBufferFactory(allocator).wrap(allocator.buffer().writeBytes(body));
			MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/catalog")
				.contentType(MediaType.APPLICATION_JSON)
				.contentLength(body.length)
				.body(Flux.just(buffer)));

			StepVerifier.create(filter().filter(exchange, this.forwardingChain)).expectComplete().verify(TIMEOUT);

			// One reference is left: the copy the gateway caches to forward, which it
			// releases itself once the exchange is over.
			assertThat(buffer.getNativeBuffer().refCnt()).isEqualTo(1);
			buffer.getNativeBuffer().release();
		}
	}

	/**
	 * A filter whose contract is already read. The first exchange through a route is
	 * handed the contract by the thread that read it; every later one finds it there, and
	 * those are the ones whose thread says something.
	 */
	private GatewayFilter filter() {
		OpenapiValidationGatewayFilterFactory factory = new OpenapiValidationGatewayFilterFactory(
				new OpenapiContractRegistry(new DefaultResourceLoader()), this.properties,
				new ValidationMetrics(new SimpleMeterRegistry()));
		GatewayFilter filter = factory.apply(new Config().setSpecUrl("classpath:openapi/limits.yaml"));
		StepVerifier
			.create(filter.filter(exchange(MockServerHttpRequest.get("/catalog").build()), (exchange) -> Mono.empty()))
			.expectComplete()
			.verify(TIMEOUT);
		return filter;
	}

	private static GatewayFilterChain responding(String body) {
		return (exchange) -> {
			exchange.getResponse().setStatusCode(HttpStatus.OK);
			exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponse().getHeaders().setContentLength(bytes.length);
			return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
		};
	}

	private static String items(int count) {
		return "[" + String.join(",", Collections.nCopies(count, "{\"name\":\"pen\",\"price\":1.25}")) + "]";
	}

	private static MockServerHttpRequest jsonPost(String body) {
		return MockServerHttpRequest.post("/catalog")
			.contentType(MediaType.APPLICATION_JSON)
			.contentLength(body.getBytes(StandardCharsets.UTF_8).length)
			.body(body);
	}

	private static MockServerWebExchange exchange(MockServerHttpRequest request) {
		return MockServerWebExchange.from(request);
	}

	@SuppressWarnings("unchecked")
	private static String attribute(MockServerWebExchange exchange, String name) {
		Object attributes = exchange.getAttribute(ValidationAttributes.VALIDATION_ATTRIBUTES_ATTR);
		return (attributes != null) ? ((Map<String, String>) attributes).get(name) : null;
	}

}
