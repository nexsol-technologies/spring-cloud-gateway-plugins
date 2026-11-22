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

import java.util.List;

import ch.nexsol.gateway.validation.OpenapiContractRegistry;
import ch.nexsol.gateway.validation.OpenapiValidationProperties;
import ch.nexsol.gateway.validation.ValidationMetrics;
import ch.nexsol.gateway.validation.factory.OpenapiValidationGatewayFilterFactory.Config;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the filter writes to the log for what it does on every exchange. A line
 * per request is written by the thread serving it, and a compressed or chunked body is
 * ordinary traffic: said once it is information, said for each of them it is a cost.
 */
class OpenapiValidationLoggingTests {

	private static final GatewayFilterChain FORWARDING = (exchange) -> Mono.empty();

	private final OpenapiValidationProperties properties = new OpenapiValidationProperties();

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private Logger logger;

	private Level level;

	@BeforeEach
	void listen() {
		this.logger = (Logger) LoggerFactory.getLogger("ch.nexsol.gateway.validation");
		this.level = this.logger.getLevel();
		this.logger.setLevel(Level.DEBUG);
		this.appender.start();
		this.logger.addAppender(this.appender);
	}

	@AfterEach
	void stopListening() {
		this.logger.detachAppender(this.appender);
		this.logger.setLevel(this.level);
	}

	@Test
	void saysOnceThatTheBodiesOfARouteAreNotValidated() {
		GatewayFilter filter = filter("classpath:openapi/bookstore.yaml");

		for (int request = 0; request < 3; request++) {
			StepVerifier.create(filter.filter(chunkedPost(), FORWARDING)).verifyComplete();
		}

		assertThat(logged(Level.INFO, "Not validating the request bod")).hasSize(1);
	}

	@Test
	void saysItAgainForAnotherReason() {
		this.properties.getRequest().setMaxBodySize(DataSize.ofBytes(4));
		GatewayFilter filter = filter("classpath:openapi/bookstore.yaml");

		StepVerifier.create(filter.filter(chunkedPost(), FORWARDING)).verifyComplete();
		StepVerifier.create(filter.filter(announcedPost(), FORWARDING)).verifyComplete();
		StepVerifier.create(filter.filter(announcedPost(), FORWARDING)).verifyComplete();

		assertThat(logged(Level.INFO, "Not validating the request bod")).hasSize(2);
	}

	@Test
	void reportsAContractThatCannotBeReadOnceRatherThanForEveryRequest() {
		GatewayFilter filter = filter("classpath:openapi/does-not-exist.yaml");

		for (int request = 0; request < 3; request++) {
			StepVerifier
				.create(filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/books").build()),
						FORWARDING))
				.verifyComplete();
		}

		assertThat(logged(Level.ERROR, "could not be read")).hasSize(1);
	}

	private List<ILoggingEvent> logged(Level level, String text) {
		return this.appender.list.stream()
			.filter((event) -> event.getLevel() == level && event.getFormattedMessage().contains(text))
			.toList();
	}

	private GatewayFilter filter(String contract) {
		OpenapiValidationGatewayFilterFactory factory = new OpenapiValidationGatewayFilterFactory(
				new OpenapiContractRegistry(new DefaultResourceLoader()), this.properties,
				new ValidationMetrics(new SimpleMeterRegistry()));
		return factory.apply(new Config().setSpecUrl(contract));
	}

	private static MockServerWebExchange chunkedPost() {
		return MockServerWebExchange.from(MockServerHttpRequest.post("/books")
			.contentType(MediaType.APPLICATION_JSON)
			.body("{\"title\":\"Dune\",\"author\":\"Herbert\"}"));
	}

	private static MockServerWebExchange announcedPost() {
		String body = "{\"title\":\"Dune\",\"author\":\"Herbert\"}";
		return MockServerWebExchange.from(MockServerHttpRequest.post("/books")
			.contentType(MediaType.APPLICATION_JSON)
			.contentLength(body.length())
			.body(body));
	}

}
