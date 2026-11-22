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

import java.security.Principal;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * Reads the JWT a principal was authenticated with.
 * <p>
 * This class links against the resource server and JOSE jars, which the plugins leave
 * optional: call it only from code that already runs with them on the classpath.
 */
public final class PrincipalJwt {

	private PrincipalJwt() {
	}

	/**
	 * Returns the JWT behind a principal: the token of a resource server authentication,
	 * else the principal of an authentication that holds a {@link Jwt} itself.
	 * @param principal the principal of the exchange, possibly {@code null}
	 * @return the JWT, or {@code null} when the principal carries none
	 */
	public static Jwt of(Principal principal) {
		if (principal instanceof AbstractOAuth2TokenAuthenticationToken<?> token
				&& token.getToken() instanceof Jwt jwt) {
			return jwt;
		}
		if (principal instanceof Authentication authentication && authentication.getPrincipal() instanceof Jwt jwt) {
			return jwt;
		}
		return null;
	}

}
