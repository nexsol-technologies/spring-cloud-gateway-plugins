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

package ch.nexsol.gateway.commons.security;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;

import static org.assertj.core.api.Assertions.assertThat;

class OnPluginAuthenticationConditionTests {

	private static final String PREFIX = "plugin.security";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(GuardedConfiguration.class);

	@Test
	void matchesOnALocalUser() {
		this.runner.withPropertyValues(PREFIX + ".user.password=secret")
			.run((context) -> assertThat(context).hasBean("guarded"));
	}

	@Test
	void matchesOnAnIssuer() {
		this.runner.withPropertyValues(PREFIX + ".oauth2.resourceserver.jwt.issuer-uri=https://idp/realms/x")
			.run((context) -> assertThat(context).hasBean("guarded"));
	}

	@Test
	void matchesOnAnAuthenticationBeanOfTheApplication() {
		// Registered ahead of the guarded bean, as the configuration of an application is
		// ahead of an auto-configuration: a REGISTER_BEAN condition sees what is already
		// defined.
		new ApplicationContextRunner()
			.withUserConfiguration(UserDirectoryConfiguration.class, GuardedConfiguration.class)
			.run((context) -> assertThat(context).hasBean("guarded"));
	}

	@Test
	void aBlankValueIsNoValue() {
		// The blank password of a configuration template must not contribute a chain
		// nobody can pass.
		this.runner.withPropertyValues(PREFIX + ".user.password=", PREFIX + ".oauth2.resourceserver.jwt.issuer-uri= ")
			.run((context) -> assertThat(context).doesNotHaveBean("guarded"));
	}

	@Test
	void doesNotMatchWithNothingToAuthenticateAgainst() {
		this.runner.run((context) -> assertThat(context).doesNotHaveBean("guarded"));
	}

	@Configuration(proxyBeanMethods = false)
	static class GuardedConfiguration {

		@Bean
		@ConditionalOnPluginAuthentication(prefix = PREFIX)
		String guarded() {
			return "guarded";
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UserDirectoryConfiguration {

		@Bean
		MapReactiveUserDetailsService userDetailsService() {
			return new MapReactiveUserDetailsService(
					User.withUsername("user").password("{noop}password").roles("USER").build());
		}

	}

}
