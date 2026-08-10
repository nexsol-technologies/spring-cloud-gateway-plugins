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

package ch.nexsol.gateway.ui.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Reads the roles an identity provider put in a token, wherever it put them.
 * <p>
 * Providers disagree on where roles live: a flat {@code roles} array, Keycloak's
 * {@code realm_access.roles}, a client-scoped
 * {@code resource_access.<client>.roles}&hellip; So the location is configured as a
 * dotted path into the claim set rather than guessed, and a path leading nowhere yields
 * no role rather than an error &mdash; a token simply carries none.
 */
public final class ClaimRoles {

	private static final String ROLE_PREFIX = "ROLE_";

	private ClaimRoles() {
	}

	/**
	 * Reads the roles at the given path and turns them into authorities.
	 * @param claims the claim set of the token
	 * @param path the dotted path the roles are read from, such as
	 * {@code realm_access.roles}
	 * @return the authorities, empty when the path leads to no list of roles
	 */
	public static Collection<GrantedAuthority> from(Map<String, Object> claims, String path) {
		Object value = claims;
		for (String segment : path.split("\\.")) {
			if (!(value instanceof Map<?, ?> map)) {
				return List.of();
			}
			value = map.get(segment);
		}
		if (!(value instanceof Collection<?> roles)) {
			return List.of();
		}
		Collection<GrantedAuthority> authorities = new ArrayList<>();
		for (Object role : roles) {
			if (role instanceof String name && !name.isBlank()) {
				authorities.add(new SimpleGrantedAuthority(prefixed(name)));
			}
		}
		return authorities;
	}

	/**
	 * Authorities are matched on their {@code ROLE_} prefix, and a provider may or may
	 * not already carry it: prefixing blindly would turn {@code ROLE_ADMIN} into
	 * {@code ROLE_ROLE_ADMIN}.
	 */
	private static String prefixed(String role) {
		String name = role.toUpperCase(Locale.ROOT);
		return name.startsWith(ROLE_PREFIX) ? name : ROLE_PREFIX + name;
	}

}
