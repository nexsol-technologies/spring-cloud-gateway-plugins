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

package ch.nexsol.gateway.ui.insights;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.ui.insights.ActuatorClient.EndpointUnavailable;
import ch.nexsol.gateway.ui.insights.ActuatorInstances.Instance;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests where the payload of an Actuator endpoint is decoded. A bean or a condition
 * report runs past a megabyte, and the thread that received it is an event loop the
 * proxied traffic shares: the report is parsed, and handed on, away from it.
 */
class ActuatorClientThreadingTests {

	private static final Instance OTHER = new Instance("gateway-2", "http://two:8080", false);

	private final MockServerWebExchange exchange = MockServerWebExchange
		.from(MockServerHttpRequest.get("/ui/insights/beans/data").build());

	@Test
	void handsThePayloadOnAwayFromTheThreadThatReceivedIt() {
		AtomicReference<String> thread = new AtomicReference<>();

		Map<String, Object> payload = client(json(HttpStatus.OK, "{\"contexts\":{\"gateway\":{\"beans\":{}}}}"))
			.read(this.exchange, OTHER, "beans")
			.doOnNext((ignored) -> thread.set(Thread.currentThread().getName()))
			.block();

		assertThat(payload).containsKey("contexts");
		assertThat(thread.get()).startsWith("boundedElastic");
	}

	@Test
	void reportsAPayloadThatIsNotJsonAsAnEndpointThatCouldNotBeRead() {
		StepVerifier.create(client(json(HttpStatus.OK, "<html>login</html>")).read(this.exchange, OTHER, "beans"))
			.expectError(EndpointUnavailable.class)
			.verify();
	}

	@Test
	void reportsAnErrorAnswerAsAnEndpointThatCouldNotBeRead() {
		StepVerifier.create(client(json(HttpStatus.NOT_FOUND, "{}")).read(this.exchange, OTHER, "beans"))
			.expectErrorSatisfies((error) -> {
				assertThat(error).isInstanceOf(EndpointUnavailable.class);
				assertThat(((EndpointUnavailable) error).endpoint()).isEqualTo("beans");
				assertThat(error.getMessage()).contains("404");
			})
			.verify();
	}

	private static ActuatorClient client(ClientResponse response) {
		WebClient.Builder builder = WebClient.builder().exchangeFunction((request) -> Mono.just(response));
		return new ActuatorClient(builder, new InsightsProperties(), new LocalActuator(null, null, null, "/actuator"));
	}

	private static ClientResponse json(HttpStatus status, String body) {
		return ClientResponse.create(status)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.body(body)
			.build();
	}

}
