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

import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

class LocalUserAuthenticationTests {

	@Test
	void noPasswordNoUser() {
		assertThat(LocalUserAuthentication.manager(new PluginSecurityProperties.User())).isEmpty();
		PluginSecurityProperties.User blank = new PluginSecurityProperties.User();
		blank.setPassword("  ");
		assertThat(LocalUserAuthentication.manager(blank)).isEmpty();
	}

	@Test
	void encodesAClearTextPasswordAndGrantsTheRoles() {
		PluginSecurityProperties.User user = new PluginSecurityProperties.User();
		user.setName("agent");
		user.setPassword("secret");
		user.setRoles(List.of("ADMIN", "AUDIT"));
		ReactiveAuthenticationManager manager = LocalUserAuthentication.manager(user).orElseThrow();
		StepVerifier.create(manager.authenticate(new UsernamePasswordAuthenticationToken("agent", "secret")))
			.assertNext((authentication) -> assertThat(authentication.getAuthorities())
				.extracting(GrantedAuthority::getAuthority)
				.containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_AUDIT"))
			.verifyComplete();
		StepVerifier.create(manager.authenticate(new UsernamePasswordAuthenticationToken("agent", "wrong")))
			.expectError(BadCredentialsException.class)
			.verify();
	}

	@Test
	void aBraceAloneIsClearText() {
		// Boot's own test: {id}hash is encoded, anything else is not. A clear-text
		// password
		// opening with a brace must still log in rather than fail on an unknown encoder.
		for (String password : List.of("{s3cret", "{}x")) {
			PluginSecurityProperties.User user = new PluginSecurityProperties.User();
			user.setPassword(password);
			ReactiveAuthenticationManager manager = LocalUserAuthentication.manager(user).orElseThrow();
			StepVerifier.create(manager.authenticate(new UsernamePasswordAuthenticationToken("admin", password)))
				.expectNextCount(1)
				.verifyComplete();
		}
	}

	@Test
	void takesAnEncodedPasswordAsIs() {
		PluginSecurityProperties.User user = new PluginSecurityProperties.User();
		user.setPassword("{noop}plain");
		ReactiveAuthenticationManager manager = LocalUserAuthentication.manager(user).orElseThrow();
		StepVerifier.create(manager.authenticate(new UsernamePasswordAuthenticationToken("admin", "plain")))
			.expectNextCount(1)
			.verifyComplete();
		StepVerifier.create(manager.authenticate(new UsernamePasswordAuthenticationToken("admin", "{noop}plain")))
			.expectError(BadCredentialsException.class)
			.verify();
	}

}
