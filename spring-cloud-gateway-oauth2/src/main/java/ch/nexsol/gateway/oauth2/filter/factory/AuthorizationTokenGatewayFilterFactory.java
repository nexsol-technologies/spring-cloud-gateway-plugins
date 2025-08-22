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

package ch.nexsol.gateway.oauth2.filter.factory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Valid
public class AuthorizationTokenGatewayFilterFactory
		extends AbstractGatewayFilterFactory<AuthorizationTokenGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(AuthorizationTokenGatewayFilterFactory.class);

	public static final String UNKNOWN_VALUE = "unknown";

	/**
	 * Issuers key.
	 */
	public static final String ISSUERS_KEY = "issuers";

	/**
	 * ClientIds key.
	 */
	public static final String CLIENT_IDS_KEY = "clientIds";

	/**
	 * GrantAccesses key.
	 */
	public static final String GRANT_ACCESSES_KEY = "grantAccesses";

	private final Converter<Map<String, Object>, Map<String, Object>> claimSetConverter;

	public AuthorizationTokenGatewayFilterFactory() {
		super(AuthorizationTokenGatewayFilterFactory.Config.class);
		this.claimSetConverter = MappedJwtClaimSetConverter.withDefaults(Collections.emptyMap());
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(ISSUERS_KEY, CLIENT_IDS_KEY, GRANT_ACCESSES_KEY);
	}

	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			return exchange.getPrincipal()
				.filter((principal) -> (principal) instanceof JwtAuthenticationToken)
				.cast(JwtAuthenticationToken.class)
				.map(JwtAuthenticationToken::getToken)
				.map((jwt) -> Optional.of(jwt))
				// in case if there not Spring Security, find JWT manually
				.defaultIfEmpty(extractBearer(exchange))
				.filter((jwt) -> jwt.isPresent())
				.map(Optional::get)
				.map((jwt) -> {
					String issuerId = jwt.getClaimAsString(IdTokenClaimNames.ISS);
					String clientId = Optional.ofNullable(jwt.getClaimAsString(IdTokenClaimNames.AZP))
						.orElse(jwt.getClaimAsString(IdTokenClaimNames.AUD));
					Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
					String routeId = UNKNOWN_VALUE;
					if (route != null) {
						routeId = Optional.ofNullable(route.getId()).orElse(UNKNOWN_VALUE);
					}
					if (config.checkIssuer()) {
						if (!config.getIssuers().contains(issuerId)) {
							LOG.debug(
									"Authorization Forbidden : route {} is not allowed for the client {} : issuer forbidden",
									routeId, clientId);
							throw new ResponseStatusException(HttpStatus.FORBIDDEN);
						}
						else {
							LOG.trace("issuer authorized {}", issuerId);
						}
					}
					if (config.checkClientId()) {
						if (!config.getClientIds().contains(clientId)) {
							LOG.debug("Authorization Forbidden : route {} is not allowed for the client {}", routeId,
									clientId);
							throw new ResponseStatusException(HttpStatus.FORBIDDEN);
						}
						else {
							LOG.trace("clientId authorized {}", clientId);
						}
					}
					if (config.checkGrantAccess()) {
						if (!hasAuthority(jwt.getClaims(), config.getGrantAccesses())) {
							LOG.debug(
									"Authorization Forbidden : route {} is not allowed for the client {} : resource access forbidden",
									routeId, clientId);
							throw new ResponseStatusException(HttpStatus.FORBIDDEN);
						}
						else {
							LOG.trace("resource access authorized");
						}
					}
					return exchange;
				})
				.defaultIfEmpty(exchange)
				.flatMap(chain::filter);
		};
	}

	private Optional<Jwt> extractBearer(ServerWebExchange exchange) {
		return exchange.getRequest()
			.getHeaders()
			.entrySet()
			.stream()
			.filter((entry) -> HttpHeaders.AUTHORIZATION.equals(entry.getKey()) && !entry.getValue().isEmpty())
			.findFirst()
			.map((entry) -> entry.getValue().get(0))
			.filter((header) -> header.startsWith("Bearer "))
			.map((s) -> s.replaceFirst("Bearer ", ""))
			.flatMap(this::createJwt);
	}

	public boolean hasAuthority(Map<String, Object> claims, List<GrantAccess> grantAccesses) {
		return grantAccesses.stream().allMatch((grantAccess) -> hasAuthority(claims, grantAccess));
	}

	public boolean hasAuthority(Map<String, Object> claims, GrantAccess grantAccess) {

		Collection<String> claimValues = null;
		String path = grantAccess.getJsonPath();
		Object claim;
		try {
			claim = JsonPath.read(claims, path);
		}
		catch (PathNotFoundException ex) {
			claim = null;
		}
		if (claim == null) {
			return false;
		}
		if (claim instanceof String claimStr) {
			claimValues = Arrays.asList(claimStr.split(","));
		}
		if (claim instanceof String[] claimArr) {
			claimValues = Arrays.asList(claimArr);
		}
		if (Collection.class.isAssignableFrom(claim.getClass())) {
			final var iterator = ((Collection) claim).iterator();
			if (!iterator.hasNext()) {
				claimValues = Collections.emptyList();
			}
			final var firstItem = iterator.next();
			if (firstItem instanceof String) {
				claimValues = (Collection<String>) claim;
			}
			if (Collection.class.isAssignableFrom(firstItem.getClass())) {
				claimValues = ((Collection) claim).stream()
					.flatMap((colItem) -> ((Collection) colItem).stream())
					.map(String.class::cast)
					.toList();
			}
		}
		final Collection<String> claimValuesFinal = claimValues;
		return grantAccess.getRoles().stream().allMatch((value) -> claimValuesFinal.contains(value));
	}

	private Optional<Jwt> createJwt(String bearer) {
		try {
			JWT parsedJwt = JWTParser.parse(bearer);
			JWTClaimsSet jwtClaimsSet = parsedJwt.getJWTClaimsSet();
			Map<String, Object> headers = new LinkedHashMap<>(parsedJwt.getHeader().toJSONObject());
			Map<String, Object> claims = this.claimSetConverter.convert(jwtClaimsSet.getClaims());
			return Optional.of(Jwt.withTokenValue(parsedJwt.getParsedString())
				.headers((h) -> h.putAll(headers))
				.claims((c) -> c.putAll(claims))
				.build());
		}
		catch (Exception ex) {
			LOG.error("Error when parsing bearer", ex);
			return Optional.empty();
		}
	}

	@Validated
	public static class Config {

		@Valid
		private List<@NotEmpty String> issuers = new ArrayList<>(0);

		@Valid
		private List<@NotEmpty String> clientIds = new ArrayList<>(0);

		@Valid
		private List<@Valid GrantAccess> grantAccesses = new ArrayList<>(0);

		public List<String> getIssuers() {
			return this.issuers;
		}

		public void setIssuers(List<String> issuers) {
			this.issuers = issuers;
		}

		public List<String> getClientIds() {
			return this.clientIds;
		}

		public void setClientIds(List<String> clientIds) {
			this.clientIds = clientIds;
		}

		public List<GrantAccess> getGrantAccesses() {
			return this.grantAccesses;
		}

		public void setGrantAccesses(List<GrantAccess> grantAccesses) {
			this.grantAccesses = grantAccesses;
		}

		public boolean checkIssuer() {
			return this.issuers != null && !this.issuers.isEmpty();
		}

		public boolean checkClientId() {
			return this.clientIds != null && !this.clientIds.isEmpty();
		}

		public boolean checkGrantAccess() {
			return this.grantAccesses != null && !this.grantAccesses.isEmpty();
		}

	}

	@Validated
	public static class GrantAccess {

		@NotEmpty
		private String jsonPath;

		@NotEmpty
		@Valid
		private List<@NotEmpty String> roles;

		public String getJsonPath() {
			return this.jsonPath;
		}

		public void setJsonPath(@NotEmpty String jsonPath) {
			this.jsonPath = jsonPath;
		}

		public List<String> getRoles() {
			return this.roles;
		}

		public void setRoles(List<String> roles) {
			this.roles = roles;
		}

	}

}
