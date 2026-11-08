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

package ch.nexsol.gateway.database.autoconfigure;

import java.util.Base64;

import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.database.RouteManagementPaths;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration tests for {@link GatewayDatabaseSecurityAutoConfiguration}: what the
 * chain matches, and the two cases where contributing it would make a gateway less safe
 * rather than more.
 */
class GatewayDatabaseSecurityAutoConfigurationTests {

	private static final String PREFIX = "spring.cloud.gateway.server.webflux.routes-database.security.";

	private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(GatewayDatabaseSecurityAutoConfiguration.class))
		.withUserConfiguration(RouteApiDeclarationConfiguration.class, WebFluxSecurityConfiguration.class,
				UserDirectoryConfiguration.class, ApplicationChainConfiguration.class);

	@Test
	void chainMatchesWhatChangesTheRoutingTable() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context
				.getBean("routeManagementSecurityWebFilterChain");
			assertThat(matches(chain, "/api/gateway/routes")).isTrue();
			assertThat(matches(chain, "/api/gateway/routes/12")).isTrue();
			assertThat(matches(chain, "/api/gateway/routes/available-predicates")).isTrue();
		});
	}

	@Test
	void chainDoesNotMatchTheRestOfTheGateway() {
		this.runner.run((context) -> {
			SecurityWebFilterChain chain = (SecurityWebFilterChain) context
				.getBean("routeManagementSecurityWebFilterChain");
			assertThat(matches(chain, "/api/orders")).isFalse();
			// A gateway route published under the same prefix is not this plugin.
			assertThat(matches(chain, "/api/gateway/routes/12/history")).isFalse();
			// The page over the same routes is a view of the console, governed by the
			// chain the console contributes rather than by this one.
			assertThat(matches(chain, "/ui/routes/db")).isFalse();
			assertThat(matches(chain, "/ui/routes")).isFalse();
		});
	}

	@Test
	void backsOffWhenItWouldDisplaceTheDefaultChainOfSpringSecurity() {
		// With no other chain, Spring Security serves its own "everything authenticated"
		// chain, which already covers these paths. Replacing it with one matching the
		// route management alone would leave every other path of the application open.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GatewayDatabaseSecurityAutoConfiguration.class))
			.withUserConfiguration(RouteApiDeclarationConfiguration.class, WebFluxSecurityConfiguration.class,
					UserDirectoryConfiguration.class)
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void backsOffWhenThereIsNothingToAuthenticateAgainst() {
		// Closing a door with no key behind it would leave no way through it at all.
		new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(GatewayDatabaseSecurityAutoConfiguration.class))
			.withUserConfiguration(RouteApiDeclarationConfiguration.class, WebFluxSecurityConfiguration.class,
					ApplicationChainConfiguration.class)
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void closesTheApiWithItsOwnLocalUserWhenTheApplicationHasNothing() {
		// The same context as above, given a local user: the door now has a key behind
		// it, held by this chain alone.
		standalone().withPropertyValues(PREFIX + "user.password=db-secret").run((context) -> {
			assertThat(context).hasBean("routeManagementSecurityWebFilterChain");
			call(context, null).expectStatus().isUnauthorized();
			call(context, basic("admin", "wrong")).expectStatus().isUnauthorized();
			// Authenticated, and then nobody answers: the controllers are not in this
			// context.
			call(context, basic("admin", "db-secret")).expectStatus().isNotFound();
		});
	}

	@Test
	void localUserTakesThePlaceOfTheUserDirectoryOfTheApplication() {
		this.runner.withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class))
			.withPropertyValues(PREFIX + "user.name=operator", PREFIX + "user.password={noop}plain")
			.run((context) -> {
				call(context, basic("operator", "plain")).expectStatus().isNotFound();
				call(context, basic("user", "password")).expectStatus().isUnauthorized();
			});
	}

	@Test
	void closesTheApiWithBasicAloneWithoutTheJwtJars() {
		// The JOSE and resource server jars are optional: without them the chain still
		// starts and answers Basic, and a Bearer token is just not a credential.
		standalone().withClassLoader(new FilteredClassLoader("org.springframework.security.oauth2"))
			.withPropertyValues(PREFIX + "user.password=db-secret",
					PREFIX + "oauth2.resourceserver.jwt.issuer-uri=http://localhost:59999/realms/absent")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasBean("routeManagementSecurityWebFilterChain");
				call(context, null).expectStatus().isUnauthorized();
				call(context, "Bearer anything").expectStatus().isUnauthorized();
				call(context, basic("admin", "db-secret")).expectStatus().isNotFound();
			});
	}

	@Test
	void aBlankCredentialIsNoCredential() {
		// The blank password and issuer of a configuration template must not contribute a
		// chain nobody can pass.
		standalone().withPropertyValues(PREFIX + "user.password=", PREFIX + "oauth2.resourceserver.jwt.issuer-uri=")
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void closesTheApiWithItsOwnIssuerWithoutWaitingOnItAtStartUp() {
		// The issuer answers nothing: the context still starts, and the first token is
		// what fails - as the gateway's error, since an unreachable issuer is not the
		// client's fault. Without a user directory no Basic is offered at all.
		standalone()
			.withPropertyValues(PREFIX + "oauth2.resourceserver.jwt.issuer-uri=http://localhost:59999/realms/absent")
			.run((context) -> {
				assertThat(context).hasBean("routeManagementSecurityWebFilterChain");
				call(context, null).expectStatus().isUnauthorized();
				call(context, "Bearer anything").expectStatus().is5xxServerError();
				call(context, basic("admin", "anything")).expectStatus().isUnauthorized();
			});
	}

	@Test
	void backsOffWhenThePluginPublishesNothing() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.routes-database.access=none")
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void chainCanBeDisabled() {
		this.runner
			.withPropertyValues("spring.cloud.gateway.server.webflux.routes-database.security-chain-enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean("routeManagementSecurityWebFilterChain"));
	}

	@Test
	void applicationChainWithTheSameNameWins() {
		this.runner.withUserConfiguration(NamedApplicationChainConfiguration.class)
			.run((context) -> assertThat(context.getBeansOfType(SecurityWebFilterChain.class)).hasSize(2));
	}

	/**
	 * A gateway with another chain and nothing to authenticate against: the one the
	 * plugin's own credentials are for.
	 */
	private static ReactiveWebApplicationContextRunner standalone() {
		return new ReactiveWebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(WebFluxAutoConfiguration.class,
					GatewayDatabaseSecurityAutoConfiguration.class))
			.withUserConfiguration(RouteApiDeclarationConfiguration.class, WebFluxSecurityConfiguration.class,
					ApplicationChainConfiguration.class);
	}

	private static WebTestClient.ResponseSpec call(AssertableReactiveWebApplicationContext context,
			String authorization) {
		WebTestClient.RequestHeadersSpec<?> request = WebTestClient.bindToApplicationContext(context)
			.build()
			.get()
			.uri("/api/gateway/routes");
		if (authorization != null) {
			request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request.exchange();
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
	}

	private static boolean matches(SecurityWebFilterChain chain, String path) {
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
		return Boolean.TRUE.equals(chain.matches(exchange).defaultIfEmpty(false).block());
	}

	/**
	 * Stands in for the declaration the plugin makes when it publishes its API, which is
	 * what the chain keys on: an application that switched the whole auto-configuration
	 * off serves none of these paths, and closing what nobody answers would turn a
	 * {@code 404} into a {@code 401}.
	 */
	@Configuration(proxyBeanMethods = false)
	static class RouteApiDeclarationConfiguration {

		@Bean
		SecuredPaths routeApiSecuredPaths() {
			return SecuredPaths.api(RouteManagementPaths.API.toArray(String[]::new));
		}

	}

	/**
	 * Provides the {@link ServerHttpSecurity} prototype the chain is built from.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableWebFluxSecurity
	static class WebFluxSecurityConfiguration {

	}

	/**
	 * The user directory the chain would authenticate against, kept apart so a context
	 * can be assembled without one.
	 */
	@Configuration(proxyBeanMethods = false)
	static class UserDirectoryConfiguration {

		@Bean
		MapReactiveUserDetailsService userDetailsService() {
			return new MapReactiveUserDetailsService(
					User.withUsername("user").password("{noop}password").roles("USER").build());
		}

	}

	/**
	 * An application, or another plugin, that has already taken over from the default
	 * chain of Spring Security.
	 */
	@Configuration(proxyBeanMethods = false)
	static class ApplicationChainConfiguration {

		@Bean
		SecurityWebFilterChain applicationWebFilterChain(ServerHttpSecurity http) {
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}

	}

	/**
	 * An application declaring the chain itself, under the name the plugin backs off
	 * from.
	 */
	@Configuration(proxyBeanMethods = false)
	static class NamedApplicationChainConfiguration {

		@Bean
		SecurityWebFilterChain routeManagementSecurityWebFilterChain(ServerHttpSecurity http) {
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}

	}

}
