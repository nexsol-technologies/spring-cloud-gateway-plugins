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
import java.util.Map;

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
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Whoever may call the endpoint may call every tool it serves, so the chain can ask for a
 * role rather than for any principal.
 */
class GatewayMcpRequiredRolesTests {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	private static final String PREFIX = "spring.cloud.gateway.server.webflux.mcp.security.";

	private static final String LOCAL_USER = PREFIX + "user.password=mcp-secret";

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
				ReactiveWebSecurityAutoConfiguration.class, ReactiveUserDetailsServiceAutoConfiguration.class,
				McpServerJsonMapperAutoConfiguration.class, McpServerStatelessAutoConfiguration.class,
				McpServerStatelessWebFluxAutoConfiguration.class, McpServerAnnotationScannerAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
				GatewayMcpSecurityAutoConfiguration.class))
		.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
				"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC")
		.withUserConfiguration(GatewayMcpSecurityAutoConfigurationTests.ApplicationChainConfiguration.class)
		.withPropertyValues("spring.security.user.name=app-user", "spring.security.user.password=app-secret");

	// A gateway without a user directory, for whatever the application declares instead.
	private final ReactiveWebApplicationContextRunner withoutDirectory = new ReactiveWebApplicationContextRunner()
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
	void acceptsACallerHoldingOneOfTheRequiredRoles() {
		// The local user holds ADMIN unless it is given other roles.
		this.runner.withPropertyValues(LOCAL_USER, PREFIX + "required-roles=OPERATOR,ADMIN")
			.run((context) -> call(context, basic("admin", "mcp-secret")).expectStatus().isOk());
	}

	@Test
	void refusesAnAuthenticatedCallerHoldingNoneOfTheRequiredRoles() {
		this.runner.withPropertyValues(LOCAL_USER, PREFIX + "required-roles=OPERATOR").run((context) -> {
			call(context, basic("admin", "mcp-secret")).expectStatus().isForbidden();
			call(context, null).expectStatus().isUnauthorized();
		});
	}

	@Test
	void refusesAUserOfTheDirectoryHoldingNoneOfTheRequiredRoles() {
		// The directory of the application grants USER to its user.
		this.runner.withPropertyValues(PREFIX + "required-roles=OPERATOR")
			.run((context) -> call(context, basic("app-user", "app-secret")).expectStatus().isForbidden());
	}

	@Test
	void acceptsABearerTokenCarryingTheRoleInTheClaimItIsGiven() {
		this.withoutDirectory
			.withPropertyValues(PREFIX + "required-roles=OPERATOR", PREFIX + "roles-claim=realm_access.roles")
			.withUserConfiguration(RolesDecoderConfiguration.class)
			.run((context) -> call(context, "Bearer good").expectStatus().isOk());
	}

	@Test
	void refusesABearerTokenCarryingNoneOfTheRequiredRoles() {
		this.withoutDirectory
			.withPropertyValues(PREFIX + "required-roles=AUDITOR", PREFIX + "roles-claim=realm_access.roles")
			.withUserConfiguration(RolesDecoderConfiguration.class)
			.run((context) -> call(context, "Bearer good").expectStatus().isForbidden());
		// Without the claim a token carries its scopes and no role.
		this.withoutDirectory.withPropertyValues(PREFIX + "required-roles=OPERATOR")
			.withUserConfiguration(RolesDecoderConfiguration.class)
			.run((context) -> call(context, "Bearer good").expectStatus().isForbidden());
	}

	@Test
	void comparesTheRolesWhateverTheirCase() {
		// A token's roles are read upper-cased, a user's are kept as written: the same
		// role must pass from both, however it is spelled where it is required.
		this.runner
			.withPropertyValues(LOCAL_USER, PREFIX + "user.roles=gateway-admin",
					PREFIX + "required-roles=Gateway-Admin")
			.run((context) -> call(context, basic("admin", "mcp-secret")).expectStatus().isOk());
		this.withoutDirectory
			.withPropertyValues(PREFIX + "required-roles=operator", PREFIX + "roles-claim=realm_access.roles")
			.withUserConfiguration(RolesDecoderConfiguration.class)
			.run((context) -> call(context, "Bearer good").expectStatus().isOk());
	}

	@Test
	void anAuthorityThatIsNotARoleDoesNotPassForOne() {
		this.withoutDirectory.withPropertyValues(PREFIX + "required-roles=ADMIN")
			.withUserConfiguration(PermissionManagerConfiguration.class)
			.run((context) -> call(context, basic("anyone", "anything")).expectStatus().isForbidden());
		this.withoutDirectory.withPropertyValues(PREFIX + "required-roles=ADMIN")
			.withUserConfiguration(RoleManagerConfiguration.class)
			.run((context) -> call(context, basic("anyone", "anything")).expectStatus().isOk());
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
	 * A decoder whose tokens carry the role {@code operator} where Keycloak puts it.
	 */
	@Configuration(proxyBeanMethods = false)
	static class RolesDecoderConfiguration {

		@Bean
		ReactiveJwtDecoder applicationJwtDecoder() {
			return (token) -> token.equals("good") ? Mono.just(Jwt.withTokenValue(token)
				.header("alg", "none")
				.subject("alice")
				.claim("realm_access", Map.of("roles", List.of("operator")))
				.build()) : Mono.error(new BadJwtException("bad token"));
		}

	}

	/**
	 * An application authenticating anyone and granting the authority {@code ADMIN}, a
	 * permission of its own rather than a role.
	 */
	@Configuration(proxyBeanMethods = false)
	static class PermissionManagerConfiguration {

		@Bean
		ReactiveAuthenticationManager applicationAuthenticationManager() {
			return (authentication) -> Mono.just(UsernamePasswordAuthenticationToken
				.authenticated(authentication.getName(), null, List.of(new SimpleGrantedAuthority("ADMIN"))));
		}

	}

	/**
	 * The same application granting the role.
	 */
	@Configuration(proxyBeanMethods = false)
	static class RoleManagerConfiguration {

		@Bean
		ReactiveAuthenticationManager applicationAuthenticationManager() {
			return (authentication) -> Mono.just(UsernamePasswordAuthenticationToken
				.authenticated(authentication.getName(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
		}

	}

}
