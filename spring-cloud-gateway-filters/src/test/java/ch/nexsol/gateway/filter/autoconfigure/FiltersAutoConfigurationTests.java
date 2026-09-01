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
import ch.nexsol.gateway.filter.factory.MaintenanceGatewayFilterFactory;
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
			assertThat(context).hasSingleBean(MaintenanceGatewayFilterFactory.class);
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

	/**
	 * The application client is left alone <em>and</em> left out of the verification: it
	 * may carry a base URL, the application's own credentials, or be load balanced, none
	 * of which belong on a call to Google.
	 */
	@Test
	void applicationWebClientIsNeitherReplacedNorBorrowed() {
		WebClient applicationWebClient = WebClient.create();
		this.runner.withBean("applicationWebClient", WebClient.class, () -> applicationWebClient).run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean("applicationWebClient", WebClient.class)).isSameAs(applicationWebClient);
			assertThat(context).hasBean("recaptchaWebClient");
			assertThat(context.getBean("recaptchaWebClient", WebClient.class)).isNotSameAs(applicationWebClient);
			assertThat(context).hasSingleBean(RecaptchaGatewayFilterFactory.class);
		});
	}

	@Test
	void aRecaptchaWebClientDeclaredByTheApplicationWins() {
		WebClient dedicated = WebClient.create();
		this.runner.withBean("recaptchaWebClient", WebClient.class, () -> dedicated).run((context) -> {
			assertThat(context).hasSingleBean(WebClient.class);
			assertThat(context.getBean("recaptchaWebClient", WebClient.class)).isSameAs(dedicated);
		});
	}

	@Test
	void correlationIdFilterCanBeDisabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.webfilter.correlation-id.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(CorrelationIdFilter.class));
	}

}
