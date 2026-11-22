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
import java.util.concurrent.atomic.AtomicBoolean;

import ch.nexsol.gateway.validation.OpenapiContractRegistry;
import ch.nexsol.gateway.validation.OpenapiValidationProperties;
import ch.nexsol.gateway.validation.ValidationMetrics;
import ch.nexsol.gateway.validation.ValidationMode;
import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory.Config;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests what becomes of the upstream response the filter replaces without having read it:
 * a body it did not buffer because it is compressed, and a status the contract does not
 * declare.
 * <p>
 * The gateway hands the filter the body of the upstream as a publisher, and only releases
 * the upstream connection when writing it is cancelled or fails. A body replaced without
 * ever being subscribed to completes neither way, so the connection never goes back to
 * the pool.
 */
class OpenapiValidationReplacedResponseTests {

	private final OpenapiValidationProperties properties = new OpenapiValidationProperties();

	private final AtomicBoolean subscribed = new AtomicBoolean();

	private final AtomicBoolean cancelled = new AtomicBoolean();

	@BeforeEach
	void enforceTheResponses() {
		this.properties.getResponse().setMode(ValidationMode.ENFORCE);
	}

	@Test
	void cancelsTheUpstreamBodyItReplaces() {
		MockServerWebExchange exchange = exchange();
		// A body that never ends, the way a stream does: draining it would never answer.
		Flux<DataBuffer> upstream = Flux.<DataBuffer>never()
			.doOnSubscribe((subscription) -> this.subscribed.set(true))
			.doOnCancel(() -> this.cancelled.set(true));

		StepVerifier.create(filter().filter(exchange, compressed(HttpStatus.INTERNAL_SERVER_ERROR, upstream)))
			.expectComplete()
			.verify(Duration.ofSeconds(5));

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(this.subscribed).isTrue();
		assertThat(this.cancelled).isTrue();
	}

	@Test
	void releasesTheBuffersTheUpstreamBodyAlreadyHolds() {
		MockServerWebExchange exchange = exchange();
		NettyDataBuffer buffer = pooled("compressed");

		StepVerifier.create(filter().filter(exchange, compressed(HttpStatus.INTERNAL_SERVER_ERROR, Flux.just(buffer))))
			.expectComplete()
			.verify(Duration.ofSeconds(5));

		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
		assertThat(buffer.getNativeBuffer().refCnt()).isZero();
	}

	@Test
	void dropsTheEncodingOfTheResponseItReplaces() {
		MockServerWebExchange exchange = exchange();

		StepVerifier.create(filter().filter(exchange, compressed(HttpStatus.INTERNAL_SERVER_ERROR, Flux.empty())))
			.expectComplete()
			.verify(Duration.ofSeconds(5));

		MockServerHttpResponse response = exchange.getResponse();
		String body = response.getBodyAsString().block();
		assertThat(body).contains("does not honour its OpenAPI contract");
		// The replacement is plain JSON of a known length: announcing the encoding or the
		// chunking of the body it replaced would make the client misread it.
		assertThat(response.getHeaders().containsHeader(HttpHeaders.CONTENT_ENCODING)).isFalse();
		assertThat(response.getHeaders().containsHeader(HttpHeaders.TRANSFER_ENCODING)).isFalse();
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(response.getHeaders().getContentLength()).isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
	}

	@Test
	void forwardsUntouchedACompressedResponseTheContractAllows() {
		MockServerWebExchange exchange = exchange();
		NettyDataBuffer buffer = pooled("compressed");

		StepVerifier.create(filter().filter(exchange, compressed(HttpStatus.OK, Flux.just(buffer))))
			.expectComplete()
			.verify(Duration.ofSeconds(5));

		MockServerHttpResponse response = exchange.getResponse();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING)).isEqualTo("gzip");
		assertThat(response.getBodyAsString().block()).isEqualTo("compressed");
	}

	private static NettyDataBuffer pooled(String content) {
		ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
		return new NettyDataBufferFactory(allocator)
			.wrap(allocator.buffer().writeBytes(content.getBytes(StandardCharsets.UTF_8)));
	}

	private GatewayFilter filter() {
		OpenapiValidationGatewayFilterFactory factory = new OpenapiValidationGatewayFilterFactory(
				new OpenapiContractRegistry(new DefaultResourceLoader()), this.properties,
				new ValidationMetrics(new SimpleMeterRegistry()));
		return factory.apply(new Config().setSpecUrl("classpath:openapi/bookstore.yaml"));
	}

	private static MockServerWebExchange exchange() {
		return MockServerWebExchange.from(MockServerHttpRequest.get("/books/7").build());
	}

	/**
	 * A chain standing in for an upstream answering a compressed, chunked JSON body: one
	 * the filter does not read, so the status is all it can hold against the contract.
	 */
	private static GatewayFilterChain compressed(HttpStatus status, Flux<DataBuffer> body) {
		return (exchange) -> {
			exchange.getResponse().setStatusCode(status);
			HttpHeaders headers = exchange.getResponse().getHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
			headers.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
			return exchange.getResponse().writeWith(body);
		};
	}

}
