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

import java.util.ArrayList;
import java.util.Collection;

import ch.nexsol.gateway.commons.security.RolesClaim;
import reactor.core.publisher.Flux;

import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.util.StringUtils;

/**
 * What the chain of the MCP endpoint adds once it accepts Bearer tokens, kept apart
 * because it names the resource server classes: the chain reaches it only then, so the
 * plugin may leave that jar optional.
 */
final class McpBearerSupport {

	private McpBearerSupport() {
	}

	/**
	 * Makes the challenge of a {@code 401} the Bearer one, which is what an MCP client
	 * reads and what the discovery filter adds the metadata URL to &mdash; Basic is still
	 * accepted &mdash; and reads the roles of a token from the claim holding them.
	 * @param http the chain under construction, its resource server already configured
	 * @param rolesClaim the claim the roles are read from, or {@code null}
	 */
	static void configure(ServerHttpSecurity http, String rolesClaim) {
		http.exceptionHandling(
				(handling) -> handling.authenticationEntryPoint(new BearerTokenServerAuthenticationEntryPoint()));
		if (StringUtils.hasText(rolesClaim)) {
			http.oauth2ResourceServer(
					(oauth2) -> oauth2.jwt((jwt) -> jwt.jwtAuthenticationConverter(rolesConverter(rolesClaim))));
		}
	}

	private static ReactiveJwtAuthenticationConverter rolesConverter(String claim) {
		JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
		ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter((jwt) -> {
			Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
			authorities.addAll(RolesClaim.authorities(jwt.getClaims(), claim));
			return Flux.fromIterable(authorities);
		});
		return converter;
	}

}
