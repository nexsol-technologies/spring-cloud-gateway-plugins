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

package ch.nexsol.gateway.metrics.prometheus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration;
import ch.nexsol.gateway.metrics.prometheus.autoconfigure.PrometheusMetricsAutoConfiguration;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the credentials the Prometheus client carries. Authentication is optional: a
 * Prometheus that needs none must keep working with nothing configured.
 */
class PrometheusAuthenticationTests {

	private MockWebServer prometheus;

	@BeforeEach
	void startPrometheus() throws IOException {
		this.prometheus = new MockWebServer();
		this.prometheus.setDispatcher(new Dispatcher() {
			@Override
			public MockResponse dispatch(RecordedRequest request) {
				return new MockResponse().setResponseCode(200)
					.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.setBody("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
			}
		});
		this.prometheus.start();
	}

	@AfterEach
	void stopPrometheus() throws IOException {
		this.prometheus.shutdown();
	}

	private ApplicationContextRunner runnerWith(String... properties) {
		String[] all = new String[properties.length + 2];
		all[0] = "spring.cloud.gateway.server.webflux.metrics.provider=prometheus";
		all[1] = "spring.cloud.gateway.server.webflux.metrics.prometheus.url=" + this.prometheus.url("/");
		System.arraycopy(properties, 0, all, 2, properties.length);
		return new ApplicationContextRunner().withBean("webClientBuilder", WebClient.Builder.class, WebClient::builder)
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
					PrometheusMetricsAutoConfiguration.class, MetricsAutoConfiguration.class))
			.withPropertyValues(all);
	}

	private String authorizationSentBy(WebClient client) throws InterruptedException {
		client.get().uri("/api/v1/query?query=up").retrieve().bodyToMono(String.class).block();
		return this.prometheus.takeRequest().getHeader(HttpHeaders.AUTHORIZATION);
	}

	@Test
	void sendsNoCredentialWhenNoneIsConfigured() {
		runnerWith().run((context) -> {
			WebClient client = context.getBean("prometheusMetricsWebClient", WebClient.class);
			// A Prometheus that needs no authentication must keep working untouched.
			assertThat(authorizationSentBy(client)).isNull();
		});
	}

	@Test
	void sendsBasicCredentialsWhenAUserNameIsConfigured() {
		runnerWith("spring.cloud.gateway.server.webflux.metrics.prometheus.username=gateway",
				"spring.cloud.gateway.server.webflux.metrics.prometheus.password=s3cret")
			.run((context) -> {
				WebClient client = context.getBean("prometheusMetricsWebClient", WebClient.class);
				String expected = "Basic "
						+ Base64.getEncoder().encodeToString("gateway:s3cret".getBytes(StandardCharsets.UTF_8));
				assertThat(authorizationSentBy(client)).isEqualTo(expected);
			});
	}

	@Test
	void sendsTheBearerTokenWhenOneIsConfigured() {
		runnerWith("spring.cloud.gateway.server.webflux.metrics.prometheus.token=abc123").run((context) -> {
			WebClient client = context.getBean("prometheusMetricsWebClient", WebClient.class);
			assertThat(authorizationSentBy(client)).isEqualTo("Bearer abc123");
		});
	}

	@Test
	void prefersTheBasicCredentialsOverTheToken() {
		runnerWith("spring.cloud.gateway.server.webflux.metrics.prometheus.username=gateway",
				"spring.cloud.gateway.server.webflux.metrics.prometheus.password=s3cret",
				"spring.cloud.gateway.server.webflux.metrics.prometheus.token=abc123")
			.run((context) -> {
				WebClient client = context.getBean("prometheusMetricsWebClient", WebClient.class);
				assertThat(authorizationSentBy(client)).startsWith("Basic ");
			});
	}

	@Test
	void letsTheApplicationSupplyItsOwnClient() {
		runnerWith("spring.cloud.gateway.server.webflux.metrics.prometheus.token=ignored")
			.withUserConfiguration(CustomClientConfiguration.class)
			.run((context) -> {
				WebClient client = context.getBean("prometheusMetricsWebClient", WebClient.class);
				// The escape hatch for what properties cannot express: mTLS, OAuth2, a
				// token that rotates.
				assertThat(authorizationSentBy(client)).isEqualTo("Custom whatever");
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomClientConfiguration {

		@Bean
		WebClient prometheusMetricsWebClient(WebClient.Builder builder, PrometheusMetricsProperties properties) {
			return builder.baseUrl(properties.getUrl())
				.defaultHeader(HttpHeaders.AUTHORIZATION, "Custom whatever")
				.build();
		}

	}

}
