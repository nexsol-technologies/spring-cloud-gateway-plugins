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

import java.net.URI;
import java.util.List;

import ch.nexsol.gateway.mcp.server.oauth2.ProtectedResource;
import org.junit.jupiter.api.Test;

import org.springframework.ai.mcp.server.common.autoconfigure.McpServerJsonMapperAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStatelessAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.StatelessServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.mcp.server.webflux.autoconfigure.McpServerStatelessWebFluxAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.test.web.reactive.server.HttpHandlerConnector;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the endpoint publishes about itself for a client to find the authorization server,
 * where the configuration is less plain than one issuer and no base path.
 */
class GatewayMcpDiscoveryTests {

	private static final String TOOLS_LIST = """
			{"jsonrpc":"2.0","id":1,"method":"tools/list"}""";

	private static final String PREFIX = "spring.cloud.gateway.server.webflux.mcp.security.";

	private static final String LOCAL_USER = PREFIX + "user.password=mcp-secret";

	private static final String PUBLISHED = PREFIX + "oauth2.authorization-servers=https://idp/realms/x";

	private static final String CONSOLE_ISSUER = "spring.cloud.gateway.server.webflux.ui.security.oauth2.resourceserver.jwt.issuer-uri=https://idp/realms/console";

	private static final String MCP_ISSUER = PREFIX + "oauth2.resourceserver.jwt.issuer-uri=https://idp/realms/mcp";

	private static final URI REQUEST = URI.create("http://gateway/mcp");

	private final ReactiveWebApplicationContextRunner secured = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class,
				ReactiveWebSecurityAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
				McpServerStatelessAutoConfiguration.class, McpServerStatelessWebFluxAutoConfiguration.class,
				McpServerAnnotationScannerAutoConfiguration.class,
				StatelessServerSpecificationFactoryAutoConfiguration.class, GatewayMcpAutoConfiguration.class,
				GatewayMcpSecurityAutoConfiguration.class))
		.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
				"spring.ai.mcp.server.protocol=STATELESS", "spring.ai.mcp.server.type=ASYNC")
		.withUserConfiguration(GatewayMcpSecurityAutoConfigurationTests.ApplicationChainConfiguration.class);

	private final ReactiveWebApplicationContextRunner unsecured = new ReactiveWebApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(GatewayMcpAutoConfiguration.class, McpServerJsonMapperAutoConfiguration.class,
						McpServerStatelessAutoConfiguration.class, McpServerStatelessWebFluxAutoConfiguration.class))
		.withPropertyValues("spring.cloud.gateway.server.webflux.mcp.enabled=true",
				"spring.ai.mcp.server.protocol=STATELESS");

	@Test
	void publishesTheIssuerOfThePluginWithTheConsoleThereToo() {
		// Given an issuer of its own, the chain of the plugin answers the endpoint ahead
		// of the console's: its issuer is the one a client must be sent to.
		this.unsecured.withPropertyValues(CONSOLE_ISSUER, MCP_ISSUER)
			.withBean("gatewayUiSecurityWebFilterChain", Object.class, Object::new)
			.run((context) -> assertThat(context.getBean(ProtectedResource.class).metadata(REQUEST))
				.containsEntry("authorization_servers", List.of("https://idp/realms/mcp")));
	}

	@Test
	void keepsTheBasicChallengeOfTheChainBehindTheBearerOne() {
		// The chain accepts Basic alone here. Replacing its challenge would leave a
		// client no way of learning that Basic is what passes.
		this.secured.withPropertyValues(LOCAL_USER, PUBLISHED)
			.run((context) -> WebTestClient.bindToApplicationContext(context)
				.configureClient()
				.baseUrl("http://localhost")
				.build()
				.post()
				.uri("/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.bodyValue(TOOLS_LIST)
				.exchange()
				.expectStatus()
				.isUnauthorized()
				.expectHeader()
				.valueEquals(HttpHeaders.WWW_AUTHENTICATE,
						"Bearer resource_metadata=\"http://localhost/.well-known/oauth-protected-resource/mcp\"",
						"Basic realm=\"Realm\""));
	}

	@Test
	void derivesTheResourceAndTheMetadataUrlUnderTheBasePathOfTheApplication() {
		this.secured.withPropertyValues(LOCAL_USER, PUBLISHED, "spring.webflux.base-path=/gw").run((context) -> {
			// Bound to the handler Spring Boot builds, which is where the base path is
			// applied.
			WebTestClient client = WebTestClient
				.bindToServer(new HttpHandlerConnector(context.getBean(HttpHandler.class)))
				.baseUrl("http://localhost")
				.build();
			client.post()
				.uri("/gw/mcp")
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.bodyValue(TOOLS_LIST)
				.exchange()
				.expectStatus()
				.isUnauthorized()
				.expectHeader()
				.value(HttpHeaders.WWW_AUTHENTICATE, (challenge) -> assertThat(challenge)
					.contains("resource_metadata=\"http://localhost/gw/.well-known/oauth-protected-resource/mcp\""));
			// The document is where the challenge says, and names the URL the client
			// connects to.
			client.get()
				.uri("/gw/.well-known/oauth-protected-resource/mcp")
				.exchange()
				.expectStatus()
				.isOk()
				.expectBody()
				.jsonPath("$.resource")
				.isEqualTo("http://localhost/gw/mcp");
		});
	}

	@Test
	void servesAResourceThatIsAnOriginAtTheRootWrittenWithASlashOrNot() {
		for (String origin : List.of("https://api.example.com", "https://api.example.com/")) {
			ProtectedResource resource = new ProtectedResource(origin, "/mcp", List.of("https://idp/realms/x"),
					List.of());
			assertThat(resource.metadataPaths()).containsExactly("/.well-known/oauth-protected-resource");
			assertThat(resource.metadataUrl(REQUEST))
				.isEqualTo("https://api.example.com/.well-known/oauth-protected-resource");
		}
	}

}
