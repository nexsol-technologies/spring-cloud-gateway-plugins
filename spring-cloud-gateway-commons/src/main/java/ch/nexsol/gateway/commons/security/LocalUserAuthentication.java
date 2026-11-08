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
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

/**
 * The local user of a plugin as an authentication manager, held by the chain of the
 * plugin alone rather than published as a {@code ReactiveUserDetailsService}, so that it
 * neither competes with nor replaces the authentication the rest of the application is
 * built on.
 */
public final class LocalUserAuthentication {

	/**
	 * The shape of a password already encoded, an encoder id in braces then the hash: the
	 * test Spring Boot applies to {@code spring.security.user.password}. A brace that
	 * opens nothing is clear text.
	 */
	private static final Pattern ENCODED = Pattern.compile("^\\{.+}.*$");

	private LocalUserAuthentication() {
	}

	/**
	 * Builds the manager authenticating the local user. Without a password there is no
	 * local user.
	 * @param user the local user
	 * @return the manager, empty without a password
	 */
	public static Optional<ReactiveAuthenticationManager> manager(PluginSecurityProperties.User user) {
		return manager(user.getName(), user.getPassword(), user.getRoles());
	}

	/**
	 * Builds the manager authenticating a local user. Without a password there is no
	 * local user.
	 * @param name the name of the user
	 * @param password the password, in clear text or already encoded
	 * @param roles the roles, without their {@code ROLE_} prefix
	 * @return the manager, empty without a password
	 */
	public static Optional<ReactiveAuthenticationManager> manager(String name, String password, List<String> roles) {
		if (!StringUtils.hasText(password)) {
			return Optional.empty();
		}
		PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		// A password already carrying the id of the encoder that produced it is taken as
		// is, which is how a deployment keeps the clear text out of its configuration.
		String encoded = ENCODED.matcher(password).matches() ? password : encoder.encode(password);
		UserDetails details = User.withUsername(name).password(encoded).roles(roles.toArray(String[]::new)).build();
		UserDetailsRepositoryReactiveAuthenticationManager manager = new UserDetailsRepositoryReactiveAuthenticationManager(
				new MapReactiveUserDetailsService(details));
		manager.setPasswordEncoder(encoder);
		return Optional.of(manager);
	}

}
