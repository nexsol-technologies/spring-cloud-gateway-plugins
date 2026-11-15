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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Who answers the endpoint on a gateway that also runs the console. An MCP client
 * presents credentials of its own and never goes through the login of the console: given
 * a user or an issuer, the chain of the plugin is the one that answers.
 */
class GatewayMcpOwnChainTests {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	private static final String PREFIX = "spring.cloud.gateway.server.webflux.mcp.security.";

	private static final String LOCAL_USER = PREFIX + "user.password=mcp-secret";

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
		.withUserConfiguration(GatewayMcpSecurityAutoConfigurationTests.ApplicationChainConfiguration.class,
				ConsoleChainConfiguration.class);

	@Test
	void answersItsOwnUserAheadOfTheConsole() {
		this.runner.withPropertyValues(LOCAL_USER).run((context) -> {
			call(context, basic("admin", "mcp-secret")).expectStatus().isOk();
			// The user of the console is not a user of the endpoint.
			call(context, basic("console-admin", "console-secret")).expectStatus().isUnauthorized();
			call(context, null).expectStatus().isUnauthorized();
		});
	}

	@Test
	void validatesABearerTokenAgainstItsOwnIssuerAheadOfTheConsole() {
		// The issuer answers nothing, which is how the chain of the plugin shows: it is
		// the one that went and asked. The console knows no token and answers 401.
		this.runner.withPropertyValues(OWN_ISSUER)
			.run((context) -> call(context, "Bearer anything").expectStatus().is5xxServerError());
	}

	@Test
	void asksForItsOwnRequiredRolesAheadOfTheConsole() {
		this.runner
			.withPropertyValues(LOCAL_USER, PREFIX + "user.roles=READER", PREFIX + "required-roles=GATEWAY_ADMIN")
			.run((context) -> call(context, basic("admin", "mcp-secret")).expectStatus().isForbidden());
		this.runner
			.withPropertyValues(LOCAL_USER, PREFIX + "user.roles=GATEWAY_ADMIN",
					PREFIX + "required-roles=GATEWAY_ADMIN")
			.run((context) -> call(context, basic("admin", "mcp-secret")).expectStatus().isOk());
	}

	@Test
	void leavesTheEndpointToTheConsoleGivenNoCredentialsOfItsOwn() {
		// The endpoint must never be left to no chain at all: without a user or an
		// issuer of its own, it stays behind the console as it always was.
		this.runner.run((context) -> {
			call(context, basic("console-admin", "console-secret")).expectStatus().isOk();
			call(context, basic("app-user", "app-secret")).expectStatus().isUnauthorized();
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

	/**
	 * Stands for the chain of the console: its bean name, its order, the endpoint among
	 * the paths it governs, and a user of its own.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ConsoleChainConfiguration {

		@Bean
		@Order(Ordered.HIGHEST_PRECEDENCE + 300)
		SecurityWebFilterChain gatewayUiSecurityWebFilterChain(ServerHttpSecurity http) {
			http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/mcp"));
			http.csrf(ServerHttpSecurity.CsrfSpec::disable);
			http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
			http.authenticationManager(new UserDetailsRepositoryReactiveAuthenticationManager(
					new MapReactiveUserDetailsService(User.withUsername("console-admin")
						.password("{noop}console-secret")
						.roles("ADMIN")
						.build())));
			http.httpBasic(withDefaults());
			return http.build();
		}

	}

}
