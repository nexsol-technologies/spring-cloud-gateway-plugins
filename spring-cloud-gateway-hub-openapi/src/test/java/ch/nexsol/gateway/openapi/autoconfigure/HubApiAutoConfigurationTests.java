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

package ch.nexsol.gateway.openapi.autoconfigure;

import java.nio.charset.StandardCharsets;

import ch.nexsol.gateway.openapi.hub.OpenapiService;
import ch.nexsol.gateway.openapi.hub.SpringDocOpenapiRoutes;
import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import ch.nexsol.gateway.openapi.hub.filter.OpenapiModifyResponseBodyGatewayFilterFactory;
import org.junit.jupiter.api.Test;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.GzipMessageBodyResolver;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Auto-configuration tests for {@link HubApiAutoConfiguration}, checking that the hub
 * keeps serving the statically configured contracts when the application has no discovery
 * client, and that the rewriting reads a document with readers of its own rather than
 * with the ones bounding the traffic the gateway routes.
 */
class HubApiAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HubApiAutoConfiguration.class))
		.withUserConfiguration(GatewayScaffoldingConfiguration.class)
		.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri=http://gateway.example");

	@Test
	void nothingIsRegisteredWhenTheHubIsDisabled() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean(SpringDocOpenapiRoutes.class));
	}

	@Test
	void discoveryBeansBackOffWithoutDiscoveryClient() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(SpringDocOpenapiRoutes.class);
				assertThat(context).doesNotHaveBean(OpenapiService.class);
				assertThat(context).doesNotHaveBean(HubDiscoveryRouteLocator.class);
			});
	}

	@Test
	void discoveryBeansAreRegisteredWithDiscoveryClient() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.withUserConfiguration(DiscoveryScaffoldingConfiguration.class)
			.run((context) -> {
				assertThat(context).hasSingleBean(OpenapiService.class);
				assertThat(context).hasSingleBean(HubDiscoveryRouteLocator.class);
			});
	}

	@Test
	void rewritesADocumentLargerThanTheDefaultCeilingOfTheCodecs() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true")
			.run((context) -> {
				OpenapiModifyResponseBodyGatewayFilterFactory factory = context
					.getBean(OpenapiModifyResponseBodyGatewayFilterFactory.class);
				StepVerifier.create(rewritten(factory, document(400 * 1024)))
					.assertNext((body) -> assertThat(body).contains("\"url\":\"http://gateway.example/service-a\""))
					.verifyComplete();
			});
	}

	@Test
	void refusesADocumentLargerThanTheConfiguredMaximum() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.hub-openapi.enabled=true",
					"spring.cloud.gateway.server.webflux.hub-openapi.max-document-size=64KB")
			.run((context) -> {
				OpenapiModifyResponseBodyGatewayFilterFactory factory = context
					.getBean(OpenapiModifyResponseBodyGatewayFilterFactory.class);
				StepVerifier.create(rewritten(factory, document(400 * 1024)))
					.verifyErrorSatisfies((ex) -> assertThat(NestedExceptionUtils.getMostSpecificCause(ex))
						.isInstanceOf(DataBufferLimitException.class));
			});
	}

	/**
	 * Runs a document through the rewriting filter the way the gateway does: the upstream
	 * answer is written to the response the filter decorated, and what the client would
	 * read comes back.
	 */
	private static Mono<String> rewritten(OpenapiModifyResponseBodyGatewayFilterFactory factory, byte[] document) {
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/v3/api-docs/service-a"));
		exchange.getAttributes()
			.put(ServerWebExchangeUtils.ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR, MediaType.APPLICATION_JSON_VALUE);
		GatewayFilter filter = factory
			.apply(new OpenapiModifyResponseBodyGatewayFilterFactory.Config().setPath("/service-a"));
		return filter.filter(exchange, (mutated) -> {
			mutated.getResponse().setStatusCode(HttpStatus.OK);
			return mutated.getResponse().writeWith(Mono.just(mutated.getResponse().bufferFactory().wrap(document)));
		}).then(Mono.defer(() -> exchange.getResponse().getBodyAsString()));
	}

	/**
	 * An OpenAPI document of at least the given size, padded with a description long
	 * enough to take it past the ceiling under test.
	 */
	private static byte[] document(int size) {
		return ("{\"openapi\":\"3.0.1\",\"servers\":[{\"url\":\"http://service-a:8080\"}],"
				+ "\"info\":{\"title\":\"service-a\",\"version\":\"1\",\"description\":\"" + "x".repeat(size)
				+ "\"},\"paths\":{}}")
			.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * The gateway beans the hub is built on, which the runner does not auto-configure.
	 */
	@Configuration(proxyBeanMethods = false)
	static class GatewayScaffoldingConfiguration {

		@Bean
		RouteLocator routeLocator() {
			return Flux::empty;
		}

		@Bean
		SwaggerUiConfigProperties swaggerUiConfigProperties() {
			return new SwaggerUiConfigProperties();
		}

		@Bean
		GzipMessageBodyResolver gzipMessageBodyResolver() {
			return new GzipMessageBodyResolver();
		}

	}

	/**
	 * The discovery beans an application running with a service registry would provide.
	 */
	@Configuration(proxyBeanMethods = false)
	static class DiscoveryScaffoldingConfiguration {

		@Bean
		ReactiveDiscoveryClient reactiveDiscoveryClient() {
			ReactiveDiscoveryClient discoveryClient = mock(ReactiveDiscoveryClient.class);
			given(discoveryClient.getServices()).willReturn(Flux.empty());
			return discoveryClient;
		}

		@Bean
		DiscoveryLocatorProperties discoveryLocatorProperties() {
			return new DiscoveryLocatorProperties();
		}

	}

}
