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

package ch.nexsol.gateway.mcp.server.autoconfigure;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the chain of the endpoint does at start-up with a configuration that leaves it
 * little or nothing to authenticate against. The gateway here has a chain of its own over
 * other paths and no user directory, so what the plugin is given is all there is.
 */
class GatewayMcpSecurityStartupTests {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	private static final String PREFIX = "spring.cloud.gateway.server.webflux.mcp.security.";

	private static final String LOCAL_USER = PREFIX + "user.password=mcp-secret";

	private static final String OWN_ISSUER = PREFIX
			+ "oauth2.resourceserver.jwt.issuer-uri=http://localhost:59999/realms/absent";

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
				ReactiveWebSecurityAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessAutoConfiguration.class, McpServerStatelessWebFluxAutoConfiguration.class,
				McpServerAnnotationScannerAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
				GatewayMcpSecurityAutoConfiguration.class))
		.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
				"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC")
		.withUserConfiguration(GatewayMcpSecurityAutoConfigurationTests.ApplicationChainConfiguration.class);

	@Test
	void startsWithSeveralDecodersAndAcceptsNoBearerItCouldNotChooseADecoderFor() {
		this.runner.withPropertyValues(LOCAL_USER)
			.withUserConfiguration(GatewayMcpSecurityAutoConfigurationTests.ApplicationDecoderConfiguration.class,
					SecondDecoderConfiguration.class)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				call(context, "Bearer good").expectStatus().isUnauthorized();
				call(context, basic("admin", "mcp-secret")).expectStatus().isOk();
			});
	}

	@Test
	void saysWhatToFixWhenSeveralDecodersAreAllThereIsToAuthenticateAgainst() {
		this.runner
			.withUserConfiguration(GatewayMcpSecurityAutoConfigurationTests.ApplicationDecoderConfiguration.class,
					SecondDecoderConfiguration.class)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("No credential can pass")
				.hasMessageContaining(PREFIX + "user.password")
				.hasMessageContaining("@Primary"));
	}

	@Test
	void answersBasicAloneOnAnIssuerItHasNoJarToValidateATokenAgainst() {
		this.runner.withClassLoader(new FilteredClassLoader(ReactiveJwtDecoder.class))
			.withPropertyValues(LOCAL_USER, OWN_ISSUER)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				call(context, "Bearer anything").expectStatus().isUnauthorized();
				call(context, basic("admin", "mcp-secret")).expectStatus().isOk();
			});
	}

	@Test
	void refusesToStartWhenAnIssuerItHasNoJarForIsAllThereIsToAuthenticateAgainst() {
		// Started anyway, the chain answers 401 to every caller and says nothing.
		this.runner.withClassLoader(new FilteredClassLoader(ReactiveJwtDecoder.class))
			.withPropertyValues(OWN_ISSUER)
			.run((context) -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("No credential can pass")
				.hasMessageContaining("spring-security-oauth2-resource-server"));
	}

	@Test
	void startsAndGovernsNothingWhenTheServerHasNoHttpEndpoint() {
		// The Spring AI transport is left out: there is no endpoint, hence no path for
		// the chain to match.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
					ReactiveWebSecurityAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
					GatewayMcpSecurityAutoConfiguration.class))
			.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true", LOCAL_USER)
			.withUserConfiguration(GatewayMcpSecurityAutoConfigurationTests.ApplicationChainConfiguration.class)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasBean("gatewayMcpSecurityWebFilterChain");
				call(context, null).expectStatus().isNotFound();
			});
	}

	private static WebTestClient.ResponseSpec call(AssertableReactiveWebApplicationContext context,
			String authorization) {
		WebTestClient.RequestBodySpec request = WebTestClient.bindToApplicationContext(context)
			.configureClient()
			.baseUrl("http://localhost")
			.build()
			.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request.bodyValue(TOOLS_LIST).exchange();
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
	}

	@Configuration(proxyBeanMethods = false)
	static class SecondDecoderConfiguration {

		@Bean
		ReactiveJwtDecoder otherTenantJwtDecoder() {
			return (token) -> Mono.error(new BadJwtException("bad token"));
		}

	}

}
