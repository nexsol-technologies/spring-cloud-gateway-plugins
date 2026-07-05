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

package ch.nexsol.gateway.oauth2.resourceserver.authorities;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.util.StringUtils;

/**
 * Default JWT granted authorities converter.
 */
public class ConfigurableJwtGrantedAuthoritiesConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final List<String> jsonPath;

	private final JwtGrantedAuthoritiesConverter jwtGrantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();

	/**
	 * Create a converter resolving authorities from the given JSON path expressions.
	 * @param jsonPaths the JSON path expressions locating the role claims
	 */
	public ConfigurableJwtGrantedAuthoritiesConverter(@NotEmpty List<@NotEmpty String> jsonPaths) {
		this.jsonPath = (jsonPaths != null)
				? jsonPaths.stream().map((jsonPath) -> JsonPath.compile(jsonPath).getPath()).toList()
				: Collections.emptyList();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Collection<GrantedAuthority> authorities = Stream
			.concat(this.jwtGrantedAuthoritiesConverter.convert(jwt).stream(), extractResourceRoles(jwt).stream())
			.collect(Collectors.toSet());
		return new JwtAuthenticationToken(jwt, authorities, getPrincipalClaimName(jwt));
	}

	private String getPrincipalClaimName(Jwt jwt) {
		return Optional.ofNullable(jwt.getClaimAsString(StandardClaimNames.PREFERRED_USERNAME))
			.or(() -> Optional.ofNullable(jwt.getClaimAsString(StandardClaimNames.NAME)))
			.or(() -> Optional.ofNullable(jwt.getClaimAsString(StandardClaimNames.SUB)))
			.orElse("unknown");
	}

	private Collection<? extends GrantedAuthority> extractResourceRoles(Jwt jwt) {
		return this.getByJsonPath(jwt.getClaims(), this.jsonPath.toArray(new String[] {}));
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private Collection<GrantedAuthority> getByJsonPath(Map<String, Object> claims, String... jsonPath) {
		return Stream.of(jsonPath).filter((path) -> StringUtils.hasText(path)).flatMap((path) -> {

			Object claim;
			try {
				claim = JsonPath.read(claims, path);
			}
			catch (PathNotFoundException ex) {
				claim = null;
			}
			if (claim == null) {
				return Stream.empty();
			}
			if (claim instanceof String claimStr) {
				return Stream.of(claimStr.split(","));
			}
			if (claim instanceof String[] claimArr) {
				return Stream.of(claimArr);
			}
			if (Collection.class.isAssignableFrom(claim.getClass())) {
				final var iter = ((Collection) claim).iterator();
				if (!iter.hasNext()) {
					return Stream.empty();
				}
				final var firstItem = iter.next();
				if (firstItem instanceof String) {
					return (Stream<String>) ((Collection<String>) claim).stream();
				}
				if (Collection.class.isAssignableFrom(firstItem.getClass())) {
					return (Stream<String>) ((Collection) claim).stream()
						.flatMap((colItem) -> ((Collection) colItem).stream())
						.map(String.class::cast);
				}
			}
			return Stream.empty();
		}).map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
	}

}
