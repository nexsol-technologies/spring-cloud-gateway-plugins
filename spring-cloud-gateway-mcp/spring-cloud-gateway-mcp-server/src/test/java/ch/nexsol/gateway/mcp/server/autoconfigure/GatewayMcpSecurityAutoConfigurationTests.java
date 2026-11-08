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
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMcpSecurityAutoConfigurationTests {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	private static final String PREFIX = "spring.cloud.gateway.server.webflux.mcp.security.";

	private static final String LOCAL_USER = PREFIX + "user.password=mcp-secret";

	private static final String PUBLISHED = PREFIX + "oauth2.authorization-servers=https://idp/realms/x";

	private static final String OWN_ISSUER = PREFIX
			+ "oauth2.resourceserver.jwt.issuer-uri=http://localhost:59999/realms/absent";

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
				ReactiveWebSecurityAutoConfiguration.class, ReactiveUserDetailsServiceAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
				McpServerStatelessWebFluxAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
				GatewayMcpSecurityAutoConfiguration.class))
		.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
				"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC",
				"spring.security.user.name=app-user", "spring.security.user.password=app-secret")
		.withUserConfiguration(ApplicationChainConfiguration.class);

	@Test
	void requiresAPrincipalAndAcceptsTheLocalUserOverBasic() {
		this.runner.withPropertyValues(LOCAL_USER).run((context) -> {
			assertThat(context).hasBean("gatewayMcpSecurityWebFilterChain");
			call(context, null).expectStatus().isUnauthorized();
			call(context, basic("admin", "mcp-secret")).expectStatus().isOk();
			call(context, basic("admin", "wrong")).expectStatus().isUnauthorized();
			// The local user is the chain's alone, and takes the place of the directory
			// of the application on this chain.
			call(context, basic("app-user", "app-secret")).expectStatus().isUnauthorized();
		});
	}

	@Test
	void fallsBackOnTheUserDirectoryOfTheApplicationWithoutALocalUser() {
		this.runner.run((context) -> {
			call(context, basic("app-user", "app-secret")).expectStatus().isOk();
			call(context, basic("admin", "anything")).expectStatus().isUnauthorized();
		});
	}

	@Test
	void takesAnEncodedPasswordAsIs() {
		this.runner.withPropertyValues(PREFIX + "user.name=agent", PREFIX + "user.password={noop}plain")
			.run((context) -> call(context, basic("agent", "plain")).expectStatus().isOk());
	}

	@Test
	void leavesTheRestOfTheGatewayToTheApplication() {
		this.runner.withPropertyValues(LOCAL_USER)
			.run((context) -> client(context).get().uri("/elsewhere").exchange().expectStatus().isNotFound());
	}

	@Test
	void acceptsABearerTokenOfTheDecoderTheApplicationDeclared() {
		this.runner.withPropertyValues(LOCAL_USER, PUBLISHED)
			.withUserConfiguration(ApplicationDecoderConfiguration.class)
			.run((context) -> {
				call(context, "Bearer good").expectStatus().isOk();
				call(context, "Bearer bad").expectStatus()
					.isUnauthorized()
					.expectHeader()
					.valueMatches(HttpHeaders.WWW_AUTHENTICATE,
							"Bearer error=\"invalid_token\".*resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"");
				call(context, null).expectStatus()
					.isUnauthorized()
					.expectHeader()
					.valueEquals(HttpHeaders.WWW_AUTHENTICATE,
							"Bearer resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"");
				call(context, basic("admin", "mcp-secret")).expectStatus().isOk();
				client(context).get()
					.uri("/.well-known/oauth-protected-resource/mcp")
					.exchange()
					.expectStatus()
					.isOk()
					.expectBody()
					.jsonPath("$.authorization_servers[0]")
					.isEqualTo("https://idp/realms/x");
			});
	}

	@Test
	void validatesAgainstItsOwnIssuerWithoutWaitingOnItAtStartUp() {
		// The issuer answers nothing: the context still starts, and the first token is
		// what fails - as the gateway's error, since an unreachable issuer is not the
		// client's fault.
		this.runner.withPropertyValues(LOCAL_USER, OWN_ISSUER).run((context) -> {
			call(context, "Bearer anything").expectStatus().is5xxServerError();
			call(context, basic("admin", "mcp-secret")).expectStatus().isOk();
		});
	}

	@Test
	void startsOnBearerAloneWhenTheApplicationHasNoUserDirectory() {
		// Basic is only offered with something to check the credentials against: Spring
		// Security cannot build its filter without an authentication manager, and the
		// gateway would not start.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
					ReactiveWebSecurityAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
					McpServerStatelessAutoConfiguration.class, McpServerStatelessWebFluxAutoConfiguration.class,
					McpServerAnnotationScannerAutoConfiguration.class,
					StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
					GatewayMcpSecurityAutoConfiguration.class))
			.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
					"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC")
			.withUserConfiguration(ApplicationChainConfiguration.class, ApplicationDecoderConfiguration.class)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				call(context, "Bearer good").expectStatus().isOk();
				call(context, basic("admin", "anything")).expectStatus().isUnauthorized();
			});
	}

	@Test
	void acceptsNoBearerWithoutAnIssuerNorADecoder() {
		this.runner.withPropertyValues(LOCAL_USER)
			.run((context) -> call(context, "Bearer anything").expectStatus().isUnauthorized());
	}

	@Test
	void backsOffWhenItWouldDisplaceTheDefaultChainOfSpringSecurity() {
		// With no other chain, Spring Security serves its own "everything authenticated"
		// chain, which already covers /mcp. Replacing it with one matching /mcp alone
		// would leave every other path of the application open.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
					ReactiveWebSecurityAutoConfiguration.class, ReactiveUserDetailsServiceAutoConfiguration.class,
					McpServerJsonMapperAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
					McpServerStatelessWebFluxAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
					StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
					GatewayMcpSecurityAutoConfiguration.class))
			.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
					"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC",
					"spring.security.user.name=app-user", "spring.security.user.password=app-secret", LOCAL_USER)
			.run((context) -> {
				assertThat(context).doesNotHaveBean("gatewayMcpSecurityWebFilterChain");
				// The default chain keeps its CSRF protection, which answers an anonymous
				// POST before the authentication does: refused either way.
				call(context, null).expectStatus().is4xxClientError();
				client(context).get().uri("/elsewhere").exchange().expectStatus().isUnauthorized();
			});
	}

	@Test
	void backsOffWhenThereIsNothingToAuthenticateAgainst() {
		// Closing a door with no key behind it would leave no way through it at all.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
					ReactiveWebSecurityAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
					McpServerStatelessAutoConfiguration.class, McpServerStatelessWebFluxAutoConfiguration.class,
					McpServerAnnotationScannerAutoConfiguration.class,
					StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
					GatewayMcpSecurityAutoConfiguration.class))
			.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
					"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC",
					// A blank value is no value.
					PREFIX + "user.password=")
			.withUserConfiguration(ApplicationChainConfiguration.class)
			.run((context) -> assertThat(context).doesNotHaveBean("gatewayMcpSecurityWebFilterChain"));
	}

	@Test
	void backsOffForTheChainOfTheApplicationAndForTheSwitch() {
		// The chain of the application matches nothing, so /mcp falls to no chain at all
		// and the server answers the anonymous call.
		this.runner.withUserConfiguration(OwnChainConfiguration.class)
			.run((context) -> call(context, null).expectStatus().isOk());
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.security-chain-enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean("gatewayMcpSecurityWebFilterChain"));
	}

	private static WebTestClient.ResponseSpec call(AssertableReactiveWebApplicationContext context,
			String authorization) {
		WebTestClient.RequestBodySpec request = client(context).post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM);
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request.bodyValue(TOOLS_LIST).exchange();
	}

	private static WebTestClient client(AssertableReactiveWebApplicationContext context) {
		return WebTestClient.bindToApplicationContext(context).configureClient().baseUrl("http://localhost").build();
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
	}

	@Configuration(proxyBeanMethods = false)
	static class ApplicationDecoderConfiguration {

		@Bean
		ReactiveJwtDecoder applicationJwtDecoder() {
			return (token) -> token.equals("good") ? Mono.just(Jwt.withTokenValue(token)
				.header("alg", "none")
				.subject("alice")
				.audience(List.of("gateway"))
				.build()) : Mono.error(new BadJwtException("bad token"));
		}

	}

	/**
	 * An application that has already taken over from the default chain of Spring
	 * Security, for the paths it routes.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ApplicationChainConfiguration {

		@Bean
		SecurityWebFilterChain applicationWebFilterChain(ServerHttpSecurity http) {
			http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/elsewhere/**"));
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class OwnChainConfiguration {

		@Bean
		SecurityWebFilterChain gatewayMcpSecurityWebFilterChain(ServerHttpSecurity http) {
			http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/never"));
			return http.build();
		}

	}

}
