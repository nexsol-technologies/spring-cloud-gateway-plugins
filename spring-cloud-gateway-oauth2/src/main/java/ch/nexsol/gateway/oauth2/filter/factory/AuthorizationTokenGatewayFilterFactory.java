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
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

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

/**
 * {@link org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory} that
 * authorizes a request against the bearer JWT it carries. Depending on the configuration
 * it can check the token issuer, the client id and the granted accesses (roles resolved
 * through JSON path expressions), rejecting the request with {@code 403 FORBIDDEN} when a
 * check fails.
 * <p>
 * A request carrying no exploitable token is rejected with {@code 401 UNAUTHORIZED} as
 * soon as a check is configured, unless the targeted route is flagged public, in which
 * case it is served without authentication by design.
 */
@Valid
public class AuthorizationTokenGatewayFilterFactory
		extends AbstractGatewayFilterFactory<AuthorizationTokenGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(AuthorizationTokenGatewayFilterFactory.class);

	public static final String UNKNOWN_VALUE = "unknown";

	private static final String BEARER_PREFIX = "Bearer ";

	/**
	 * Route metadata key flagging a route as publicly accessible. Mirrors
	 * {@code PublicRouteMatcher.PUBLIC_METADATA_KEY} of the routes-security module, which
	 * this module does not depend on.
	 */
	private static final String PUBLIC_METADATA_KEY = "public";

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

	/**
	 * Create a new factory bound to its {@link Config} type.
	 */
	public AuthorizationTokenGatewayFilterFactory() {
		super(AuthorizationTokenGatewayFilterFactory.Config.class);
		this.claimSetConverter = MappedJwtClaimSetConverter.withDefaults(Collections.emptyMap());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(ISSUERS_KEY, CLIENT_IDS_KEY, GRANT_ACCESSES_KEY);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> resolveJwt(exchange).switchIfEmpty(Mono.defer(() -> missingToken(config, exchange)))
			.flatMap((jwt) -> authorize(config, jwt, exchange))
			.then(Mono.defer(() -> chain.filter(exchange)));
	}

	/**
	 * Resolves the JWT carried by the exchange: the one Spring Security already
	 * authenticated when there is one, the bearer of the {@code Authorization} header
	 * otherwise.
	 * @param exchange the current exchange
	 * @return the token, or an empty result when the request carries none
	 */
	private Mono<Jwt> resolveJwt(ServerWebExchange exchange) {
		return exchange.getPrincipal()
			.ofType(JwtAuthenticationToken.class)
			.map(JwtAuthenticationToken::getToken)
			// No Spring Security in the chain: read the bearer off the request itself.
			// Deferred so the token is not parsed again when a principal was found.
			.switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(extractBearer(exchange))));
	}

	/**
	 * Decides what to do with a request carrying no exploitable token: it is denied as
	 * soon as the route configures a check, so the filter never fails open.
	 * @param config the filter configuration
	 * @param exchange the current exchange
	 * @return an empty result to let the request through, an error to deny it
	 */
	private Mono<Jwt> missingToken(Config config, ServerWebExchange exchange) {
		Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
		// A public route is served without any authentication, so a missing token is
		// expected there and must not be rejected.
		if (!config.hasCheck() || isPublic(route)) {
			return Mono.empty();
		}
		LOG.debug("Authorization Unauthorized : route {} requires a bearer token", routeId(route));
		return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

	/**
	 * Runs the configured checks against the token.
	 * @param config the filter configuration
	 * @param jwt the token carried by the request
	 * @param exchange the current exchange
	 * @return an empty result when every check passes, an error otherwise
	 */
	private Mono<Void> authorize(Config config, Jwt jwt, ServerWebExchange exchange) {
		String clientId = Optional.ofNullable(jwt.getClaimAsString(IdTokenClaimNames.AZP))
			.orElse(jwt.getClaimAsString(IdTokenClaimNames.AUD));
		if (config.checkIssuer() && !config.getIssuers().contains(jwt.getClaimAsString(IdTokenClaimNames.ISS))) {
			return forbidden(exchange, clientId, "issuer forbidden");
		}
		if (config.checkClientId() && !config.getClientIds().contains(clientId)) {
			return forbidden(exchange, clientId, "client id forbidden");
		}
		if (config.checkGrantAccess()
				&& !hasAuthority(jwt.getClaims(), config.getGrantAccesses(), config.getGrantAccessesMatch())) {
			return forbidden(exchange, clientId, "resource access forbidden");
		}
		LOG.trace("authorization granted to the client {}", clientId);
		return Mono.empty();
	}

	private Mono<Void> forbidden(ServerWebExchange exchange, String clientId, String reason) {
		LOG.debug("Authorization Forbidden : route {} is not allowed for the client {} : {}",
				routeId(exchange.getAttribute(GATEWAY_ROUTE_ATTR)), clientId, reason);
		return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN));
	}

	private static String routeId(Route route) {
		return (route != null) ? Optional.ofNullable(route.getId()).orElse(UNKNOWN_VALUE) : UNKNOWN_VALUE;
	}

	private static boolean isPublic(Route route) {
		Map<String, Object> metadata = (route != null) ? route.getMetadata() : null;
		Object flag = (metadata != null) ? metadata.get(PUBLIC_METADATA_KEY) : null;
		if (flag instanceof Boolean bool) {
			return bool;
		}
		return (flag != null) && Boolean.parseBoolean(flag.toString());
	}

	private Optional<Jwt> extractBearer(ServerWebExchange exchange) {
		// getFirst() matches the header name whatever its case, as HTTP/2 lowercases it.
		return Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
			.filter((header) -> header.startsWith(BEARER_PREFIX))
			.map((header) -> header.substring(BEARER_PREFIX.length()))
			.flatMap(this::createJwt);
	}

	/**
	 * Check whether the given JWT claims satisfy all of the configured granted accesses.
	 * @param claims the JWT claims to inspect
	 * @param grantAccesses the required granted accesses
	 * @return {@code true} if every granted access is satisfied
	 */
	public boolean hasAuthority(Map<String, Object> claims, List<GrantAccess> grantAccesses) {
		return hasAuthority(claims, grantAccesses, MatchMode.ALL);
	}

	/**
	 * Check whether the given JWT claims satisfy the configured granted accesses,
	 * combined according to the given mode.
	 * @param claims the JWT claims to inspect
	 * @param grantAccesses the required granted accesses
	 * @param match how the granted accesses are combined
	 * @return {@code true} if the granted accesses are satisfied
	 */
	public boolean hasAuthority(Map<String, Object> claims, List<GrantAccess> grantAccesses, MatchMode match) {
		return (match == MatchMode.ANY)
				? grantAccesses.stream().anyMatch((grantAccess) -> hasAuthority(claims, grantAccess))
				: grantAccesses.stream().allMatch((grantAccess) -> hasAuthority(claims, grantAccess));
	}

	boolean hasAuthority(Map<String, Object> claims, GrantAccess grantAccess) {
		Object claim;
		try {
			claim = JsonPath.read(claims, grantAccess.getJsonPath());
		}
		catch (PathNotFoundException ex) {
			claim = null;
		}
		if (claim == null) {
			return false;
		}
		Collection<String> claimValues = claimValues(claim);
		return (grantAccess.getMatch() == MatchMode.ANY)
				? grantAccess.getRoles().stream().anyMatch(claimValues::contains)
				: grantAccess.getRoles().stream().allMatch(claimValues::contains);
	}

	/**
	 * Flattens the value a JSON path resolved to into the roles it holds. A path with
	 * wildcards resolves to a list of lists, and a single claim can hold a comma
	 * separated list of roles.
	 * @param claim the resolved claim value
	 * @return the roles carried by the claim, empty when it holds none
	 */
	private static Collection<String> claimValues(Object claim) {
		if (claim instanceof String claimStr) {
			return Arrays.asList(claimStr.split(","));
		}
		if (claim instanceof Collection<?> collection) {
			return collection.stream()
				.flatMap((item) -> (item instanceof Collection<?> nested) ? nested.stream() : Stream.of(item))
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.toList();
		}
		return Collections.emptyList();
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

	/**
	 * Configuration for the {@link AuthorizationTokenGatewayFilterFactory} holding the
	 * allowed issuers, client ids and granted accesses to check.
	 */
	@Validated
	public static class Config {

		private List<@NotEmpty String> issuers = new ArrayList<>(0);

		private List<@NotEmpty String> clientIds = new ArrayList<>(0);

		private List<@Valid GrantAccess> grantAccesses = new ArrayList<>(0);

		@NotNull
		private MatchMode grantAccessesMatch = MatchMode.ALL;

		/**
		 * Return the allowed issuers.
		 * @return the issuers
		 */
		public List<String> getIssuers() {
			return this.issuers;
		}

		/**
		 * Set the allowed issuers.
		 * @param issuers the issuers to set
		 */
		public void setIssuers(List<String> issuers) {
			this.issuers = issuers;
		}

		/**
		 * Return the allowed client ids.
		 * @return the client ids
		 */
		public List<String> getClientIds() {
			return this.clientIds;
		}

		/**
		 * Set the allowed client ids.
		 * @param clientIds the client ids to set
		 */
		public void setClientIds(List<String> clientIds) {
			this.clientIds = clientIds;
		}

		/**
		 * Return the required granted accesses.
		 * @return the granted accesses
		 */
		public List<GrantAccess> getGrantAccesses() {
			return this.grantAccesses;
		}

		/**
		 * Set the required granted accesses.
		 * @param grantAccesses the granted accesses to set
		 */
		public void setGrantAccesses(List<GrantAccess> grantAccesses) {
			this.grantAccesses = grantAccesses;
		}

		/**
		 * Return how the granted accesses are combined.
		 * @return the match mode
		 */
		public MatchMode getGrantAccessesMatch() {
			return this.grantAccessesMatch;
		}

		/**
		 * Set how the granted accesses are combined: {@link MatchMode#ALL} requires every
		 * granted access, {@link MatchMode#ANY} a single one.
		 * @param grantAccessesMatch the match mode to set
		 */
		public void setGrantAccessesMatch(MatchMode grantAccessesMatch) {
			this.grantAccessesMatch = grantAccessesMatch;
		}

		/**
		 * Whether the issuer check is enabled (at least one issuer configured).
		 * @return {@code true} if the issuer must be checked
		 */
		public boolean checkIssuer() {
			return this.issuers != null && !this.issuers.isEmpty();
		}

		/**
		 * Whether the client id check is enabled (at least one client id configured).
		 * @return {@code true} if the client id must be checked
		 */
		public boolean checkClientId() {
			return this.clientIds != null && !this.clientIds.isEmpty();
		}

		/**
		 * Whether the granted access check is enabled (at least one configured).
		 * @return {@code true} if the granted accesses must be checked
		 */
		public boolean checkGrantAccess() {
			return this.grantAccesses != null && !this.grantAccesses.isEmpty();
		}

		/**
		 * Whether at least one check is configured, hence whether the route requires a
		 * token at all.
		 * @return {@code true} if the filter has something to check
		 */
		public boolean hasCheck() {
			return checkIssuer() || checkClientId() || checkGrantAccess();
		}

	}

	/**
	 * A single granted access requirement, made of a JSON path pointing to a claim and
	 * the roles that claim must contain, combined according to its match mode.
	 */
	@Validated
	public static class GrantAccess {

		@NotEmpty
		private String jsonPath;

		@NotEmpty
		private List<@NotEmpty String> roles;

		@NotNull
		private MatchMode match = MatchMode.ALL;

		/**
		 * Return the JSON path locating the claim holding the roles.
		 * @return the json path
		 */
		public String getJsonPath() {
			return this.jsonPath;
		}

		/**
		 * Set the JSON path locating the claim holding the roles.
		 * @param jsonPath the json path to set
		 */
		public void setJsonPath(@NotEmpty String jsonPath) {
			this.jsonPath = jsonPath;
		}

		/**
		 * Return the required roles.
		 * @return the roles
		 */
		public List<String> getRoles() {
			return this.roles;
		}

		/**
		 * Set the required roles.
		 * @param roles the roles to set
		 */
		public void setRoles(List<String> roles) {
			this.roles = roles;
		}

		/**
		 * Return how the required roles are combined.
		 * @return the match mode
		 */
		public MatchMode getMatch() {
			return this.match;
		}

		/**
		 * Set how the required roles are combined: {@link MatchMode#ALL} requires the
		 * claim to hold every role, {@link MatchMode#ANY} a single one.
		 * @param match the match mode to set
		 */
		public void setMatch(MatchMode match) {
			this.match = match;
		}

	}

	/**
	 * How a list of requirements is combined.
	 */
	public enum MatchMode {

		/**
		 * Every requirement must be satisfied.
		 */
		ALL,

		/**
		 * A single satisfied requirement is enough.
		 */
		ANY

	}

}
