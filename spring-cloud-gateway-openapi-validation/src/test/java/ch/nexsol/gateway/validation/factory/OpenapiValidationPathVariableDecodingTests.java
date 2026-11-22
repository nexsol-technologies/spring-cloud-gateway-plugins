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

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.nexsol.gateway.validation.OpenapiContractRegistry;
import ch.nexsol.gateway.validation.OpenapiValidationProperties;
import ch.nexsol.gateway.validation.ValidationMetrics;
import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory.Config;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that a path variable is held against the contract in the form the upstream
 * service reads it, percent-decoded. Validated as it travels, {@code %2e%2e} satisfies a
 * schema that forbids dots and reaches the service as {@code ..}, while a value the
 * contract allows is refused for the mere fact of being encoded.
 */
class OpenapiValidationPathVariableDecodingTests {

	private final AtomicBoolean forwarded = new AtomicBoolean();

	private final GatewayFilterChain forwardingChain = (exchange) -> {
		this.forwarded.set(true);
		return Mono.empty();
	};

	@Test
	void rejectsAnEncodedValueTheSchemaForbidsOnceDecoded() {
		StepVerifier.create(filter().filter(exchange("/files/%2e%2e"), this.forwardingChain))
			.verifyErrorSatisfies((error) -> {
				assertThat(error).isInstanceOf(ResponseStatusException.class);
				assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			});

		assertThat(this.forwarded).isFalse();
	}

	@Test
	void forwardsAnEncodedValueTheSchemaAllowsOnceDecoded() {
		StepVerifier.create(filter().filter(exchange("/shelves/%61%62"), this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
	}

	@Test
	void stillForwardsAPlainValueTheSchemaAllows() {
		StepVerifier.create(filter().filter(exchange("/shelves/ab"), this.forwardingChain)).verifyComplete();

		assertThat(this.forwarded).isTrue();
	}

	@Test
	void stillRejectsAPlainValueTheSchemaForbids() {
		StepVerifier.create(filter().filter(exchange("/files/a.b"), this.forwardingChain))
			.verifyError(ResponseStatusException.class);

		assertThat(this.forwarded).isFalse();
	}

	@Test
	void anEncodedSlashStaysInsideTheVariableItWasSentIn() {
		// %2F is data, not a separator: the request still resolves to /shelves/{code},
		// whose schema then refuses the slash.
		StepVerifier.create(filter().filter(exchange("/shelves/a%2Fb"), this.forwardingChain))
			.verifyErrorSatisfies((error) -> assertThat(((ResponseStatusException) error).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST));

		assertThat(this.forwarded).isFalse();
	}

	private static GatewayFilter filter() {
		OpenapiValidationGatewayFilterFactory factory = new OpenapiValidationGatewayFilterFactory(
				new OpenapiContractRegistry(new DefaultResourceLoader()), new OpenapiValidationProperties(),
				new ValidationMetrics(new SimpleMeterRegistry()));
		return factory.apply(new Config().setSpecUrl("classpath:openapi/path-variables.yaml"));
	}

	private static MockServerWebExchange exchange(String rawPath) {
		// Built from a URI, so the path keeps the encoding it is given.
		return MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, URI.create(rawPath)).build());
	}

}
