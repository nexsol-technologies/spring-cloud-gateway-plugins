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

package ch.nexsol.gateway.filter.autoconfigure;

import ch.nexsol.gateway.filter.CorrelationIdFilter;
import ch.nexsol.gateway.filter.factory.RecaptchaGatewayFilterFactory;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests for {@link FiltersAutoConfiguration}, checking that the module
 * stands alone whether or not the application provides its own web client.
 */
class FiltersAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(FiltersAutoConfiguration.class));

	@Test
	void filtersAreRegisteredWithoutWebClientBuilderBean() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(WebClient.class);
			assertThat(context).hasSingleBean(RecaptchaGatewayFilterFactory.class);
			assertThat(context).hasSingleBean(CorrelationIdFilter.class);
		});
	}

	@Test
	void applicationWebClientBuilderIsUsedWhenPresent() {
		ExchangeFunction stub = (request) -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build());
		this.runner.withBean(WebClient.Builder.class, () -> WebClient.builder().exchangeFunction(stub))
			.run((context) -> {
				assertThat(context).hasSingleBean(WebClient.class);
				HttpStatusCode status = context.getBean(WebClient.class)
					.get()
					.uri("http://recaptcha.example/verify")
					.exchangeToMono((response) -> Mono.just(response.statusCode()))
					.block();
				assertThat(status).isEqualTo(HttpStatus.ACCEPTED);
			});
	}

	@Test
	void applicationWebClientIsNotReplaced() {
		WebClient webClient = WebClient.create();
		this.runner.withBean(WebClient.class, () -> webClient).run((context) -> {
			assertThat(context).hasSingleBean(WebClient.class);
			assertThat(context.getBean(WebClient.class)).isSameAs(webClient);
		});
	}

	@Test
	void correlationIdFilterCanBeDisabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.webfilter.correlation-id.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
	}

}
