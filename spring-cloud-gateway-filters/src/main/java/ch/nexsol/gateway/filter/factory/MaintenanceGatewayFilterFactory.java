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

package ch.nexsol.gateway.filter.factory;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ServerWebExchange;

/**
 * Gateway filter factory taking a route out of service for the duration of a maintenance
 * window. While the window is open the route is not forwarded at all: the gateway answers
 * on its own with the configured status and a JSON body carrying the message to display,
 * the start of the window and its end. A window with a known end also carries it as a
 * {@code Retry-After} header.
 * <p>
 * The window is open from {@code start} inclusive to {@code end} exclusive, and an unset
 * bound is unbounded &mdash; no {@code start} means the maintenance is already on, no
 * {@code end} means it lasts until the configuration says otherwise.
 * <p>
 * A population can be let through while everyone else is held back, by authority or by
 * JWT claim. Holding any one of the configured authorities lifts the maintenance, and so
 * does satisfying the configured claims &mdash; combined with {@code allowedClaimsMatch},
 * {@link MatchMode#ANY} for a caller holding one of them, {@link MatchMode#ALL} for a
 * caller holding every one. With no exemption configured the route is closed to everyone,
 * the authenticated included.
 * <p>
 * The claims read here are those of the token Spring Security authenticated upstream,
 * whose signature, expiry and issuer have therefore already been verified. The filter
 * never reads the {@code Authorization} header itself: the claims of a token nobody
 * verified are attacker-controlled, and exempting on them would let anyone forge their
 * way past the maintenance.
 */
public class MaintenanceGatewayFilterFactory
		extends AbstractGatewayFilterFactory<MaintenanceGatewayFilterFactory.Config> {

	/**
	 * Status answered while the maintenance window is open, unless the route configures
	 * another one.
	 */
	public static final int DEFAULT_STATUS = 593;

	/**
	 * Message carried by the body when the route configures none.
	 */
	public static final String DEFAULT_MESSAGE = "This service is temporarily unavailable for maintenance.";

	private static final Logger LOG = LoggerFactory.getLogger(MaintenanceGatewayFilterFactory.class);

	private static final Pattern DELIMITER = Pattern.compile("[\\s,]+");

	private final JsonMapper mapper = JsonMapper.builder().build();

	private final Clock clock;

	/**
	 * Creates the factory reading the time from the system clock.
	 */
	public MaintenanceGatewayFilterFactory() {
		this(Clock.systemUTC());
	}

	/**
	 * Creates the factory reading the time from the given clock.
	 * @param clock the clock the maintenance window is held against
	 */
	MaintenanceGatewayFilterFactory(Clock clock) {
		super(Config.class);
		this.clock = clock;
	}

	/**
	 * Builds a filter answering the configured maintenance response while the window is
	 * open, and forwarding the request otherwise.
	 * @param config the filter configuration holding the window, the response and the
	 * exemptions
	 * @return a gateway filter enforcing the maintenance window
	 */
	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			if (!config.isOpenAt(this.clock.instant())) {
				return chain.filter(exchange);
			}
			if (!config.hasExemption()) {
				return underMaintenance(config, exchange);
			}
			// The verdict is carried by the value rather than by an empty mono: a filter
			// returns Mono<Void>, which always completes empty, so a switchIfEmpty placed
			// after chain.filter() would fire on every forwarded request and answer the
			// maintenance body on top of the response the route already wrote.
			return exchange.<Principal>getPrincipal()
				.map((principal) -> isExempt(config, principal))
				.defaultIfEmpty(Boolean.FALSE)
				.flatMap((exempt) -> exempt ? chain.filter(exchange) : underMaintenance(config, exchange));
		};
	}

	/**
	 * Answers the maintenance response without ever reaching the route.
	 */
	private Mono<Void> underMaintenance(Config config, ServerWebExchange exchange) {
		LOG.debug("Maintenance : {} {} is closed until {}", exchange.getRequest().getMethod(),
				exchange.getRequest().getPath().value(),
				(config.getEnd() != null) ? config.getEnd() : "further notice");
		byte[] body = body(config);
		ServerHttpResponse response = exchange.getResponse();
		response.setStatusCode(HttpStatusCode.valueOf(config.getStatus()));
		response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		response.getHeaders().setContentLength(body.length);
		if (config.getEnd() != null) {
			// The end of the window is exclusive, so it is the first moment a retry can
			// succeed. A window with no end promises nothing: no header at all says
			// "unknown", where any value would be a return date invented by the gateway.
			response.getHeaders().setInstant(HttpHeaders.RETRY_AFTER, config.getEnd().toInstant());
		}
		return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
	}

	private byte[] body(Config config) {
		ObjectNode node = this.mapper.createObjectNode();
		node.put("message", config.getMessage());
		node.put("start", format(config.getStart()));
		node.put("end", format(config.getEnd()));
		return this.mapper.writeValueAsBytes(node);
	}

	private static String format(OffsetDateTime bound) {
		return (bound != null) ? bound.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) : null;
	}

	/**
	 * Whether the caller belongs to the population the maintenance is lifted for.
	 */
	private static boolean isExempt(Config config, Principal principal) {
		if (!(principal instanceof Authentication authentication) || !authentication.isAuthenticated()) {
			return false;
		}
		if (hasAuthority(authentication, config.getAllowedAuthorities())) {
			return true;
		}
		if (config.getAllowedClaims().isEmpty()) {
			return false;
		}
		Jwt jwt = extractJwt(principal);
		// The emptiness of the list is settled above on purpose: allMatch answers true on
		// an empty stream, so an ALL mode with no claim configured would exempt every
		// caller carrying a token.
		return (jwt != null) && matches(config.getAllowedClaimsMatch(), config.getAllowedClaims().stream(),
				(allowed) -> hasClaimValue(jwt.getClaims(), allowed));
	}

	/**
	 * Combines the elements of a stream according to the match mode: every one of them
	 * for {@link MatchMode#ALL}, a single one for {@link MatchMode#ANY}.
	 */
	private static <T> boolean matches(MatchMode match, Stream<T> elements, Predicate<T> predicate) {
		return (match == MatchMode.ALL) ? elements.allMatch(predicate) : elements.anyMatch(predicate);
	}

	private static boolean hasAuthority(Authentication authentication, List<String> allowed) {
		return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(allowed::contains);
	}

	private static boolean hasClaimValue(Map<String, Object> claims, AllowedClaim allowed) {
		Object claim;
		try {
			claim = JsonPath.read(claims, allowed.getJsonPath());
		}
		catch (PathNotFoundException ex) {
			return false;
		}
		if (claim == null) {
			return false;
		}
		Collection<String> values = claimValues(claim);
		return matches(allowed.getMatch(), allowed.getValues().stream(), values::contains);
	}

	/**
	 * Flattens the value a JSON path resolved to into the values it holds. A path with
	 * wildcards resolves to a list of lists, and a scalar claim &mdash; the {@code true}
	 * of a feature flag &mdash; is compared as it prints.
	 * <p>
	 * A claim holding a single string is offered whole <em>and</em> split on commas and
	 * whitespace, because there is no telling which one it is: {@code scope} is space
	 * separated by RFC 6749, vendors write role lists with commas, and a claim naming one
	 * value may well contain a space of its own. Splitting on commas alone would lock the
	 * exempted population out of a route keyed on the standard {@code scope} claim.
	 * @param claim the resolved claim value
	 * @return the values carried by the claim
	 */
	private static Collection<String> claimValues(Object claim) {
		if (claim instanceof String text) {
			List<String> values = new ArrayList<>();
			values.add(text);
			Collections.addAll(values, DELIMITER.split(text.trim()));
			return values;
		}
		if (claim instanceof Collection<?> collection) {
			return collection.stream()
				.flatMap((item) -> (item instanceof Collection<?> nested) ? nested.stream() : Stream.of(item))
				.filter(Objects::nonNull)
				.map(String::valueOf)
				.toList();
		}
		return List.of(String.valueOf(claim));
	}

	private static Jwt extractJwt(Principal principal) {
		if (principal instanceof AbstractOAuth2TokenAuthenticationToken<?> token
				&& token.getToken() instanceof Jwt jwt) {
			return jwt;
		}
		if (principal instanceof Authentication authentication && authentication.getPrincipal() instanceof Jwt jwt) {
			return jwt;
		}
		return null;
	}

	/**
	 * Configuration for {@link MaintenanceGatewayFilterFactory}, holding the maintenance
	 * window, the response it answers with and the exemptions that lift it.
	 */
	@Validated
	public static class Config {

		private String message = DEFAULT_MESSAGE;

		private OffsetDateTime start;

		private OffsetDateTime end;

		@Min(400)
		@Max(599)
		private int status = DEFAULT_STATUS;

		private List<@NotEmpty String> allowedAuthorities = new ArrayList<>(0);

		private List<@Valid AllowedClaim> allowedClaims = new ArrayList<>(0);

		@NotNull
		private MatchMode allowedClaimsMatch = MatchMode.ANY;

		/**
		 * Returns the message the response body carries.
		 * @return the message to display
		 */
		public String getMessage() {
			return this.message;
		}

		/**
		 * Sets the message the response body carries.
		 * @param message the message to display
		 */
		public void setMessage(String message) {
			this.message = message;
		}

		/**
		 * Returns the moment the maintenance window opens, {@code null} when it is
		 * already open.
		 * @return the start of the window
		 */
		public OffsetDateTime getStart() {
			return this.start;
		}

		/**
		 * Sets the moment the maintenance window opens, from an ISO-8601 date and time.
		 * @param start the start of the window, unbounded when blank
		 * @throws IllegalArgumentException when the value is not an ISO-8601 date and
		 * time carrying its offset
		 */
		public void setStart(String start) {
			this.start = parse("start", start);
		}

		/**
		 * Returns the moment the maintenance window closes, {@code null} when it lasts
		 * until the configuration says otherwise.
		 * @return the end of the window
		 */
		public OffsetDateTime getEnd() {
			return this.end;
		}

		/**
		 * Sets the moment the maintenance window closes, from an ISO-8601 date and time.
		 * @param end the end of the window, unbounded when blank
		 * @throws IllegalArgumentException when the value is not an ISO-8601 date and
		 * time carrying its offset
		 */
		public void setEnd(String end) {
			this.end = parse("end", end);
		}

		/**
		 * Returns the status answered while the window is open.
		 * @return the maintenance status
		 */
		public int getStatus() {
			return this.status;
		}

		/**
		 * Sets the status answered while the window is open.
		 * @param status the maintenance status, from 400 to 599
		 */
		public void setStatus(int status) {
			this.status = status;
		}

		/**
		 * Returns the authorities lifting the maintenance for their holder.
		 * @return the exempted authorities
		 */
		public List<String> getAllowedAuthorities() {
			return this.allowedAuthorities;
		}

		/**
		 * Sets the authorities lifting the maintenance for their holder.
		 * @param allowedAuthorities the exempted authorities
		 */
		public void setAllowedAuthorities(List<String> allowedAuthorities) {
			this.allowedAuthorities = allowedAuthorities;
		}

		/**
		 * Returns the claim values lifting the maintenance for their holder.
		 * @return the exempted claims
		 */
		public List<AllowedClaim> getAllowedClaims() {
			return this.allowedClaims;
		}

		/**
		 * Sets the claim values lifting the maintenance for their holder.
		 * @param allowedClaims the exempted claims
		 */
		public void setAllowedClaims(List<AllowedClaim> allowedClaims) {
			this.allowedClaims = allowedClaims;
		}

		/**
		 * Returns how the configured claims are combined.
		 * @return the match mode
		 */
		public MatchMode getAllowedClaimsMatch() {
			return this.allowedClaimsMatch;
		}

		/**
		 * Sets how the configured claims are combined: {@link MatchMode#ANY} exempts a
		 * caller satisfying one of them, {@link MatchMode#ALL} one satisfying every one.
		 * @param allowedClaimsMatch the match mode to set
		 */
		public void setAllowedClaimsMatch(MatchMode allowedClaimsMatch) {
			this.allowedClaimsMatch = allowedClaimsMatch;
		}

		/**
		 * Whether the maintenance window is open at the given moment, {@code start}
		 * included and {@code end} excluded.
		 * @param now the moment to hold the window against
		 * @return {@code true} when the route is under maintenance
		 */
		public boolean isOpenAt(Instant now) {
			return (this.start == null || !now.isBefore(this.start.toInstant()))
					&& (this.end == null || now.isBefore(this.end.toInstant()));
		}

		/**
		 * Whether anyone at all can be let through while the window is open.
		 * @return {@code true} when an exemption is configured
		 */
		public boolean hasExemption() {
			return !this.allowedAuthorities.isEmpty() || !this.allowedClaims.isEmpty();
		}

		/**
		 * Whether the window ends after it starts. A window ending before it starts never
		 * opens, so it is rejected rather than left as a maintenance nobody would ever
		 * see.
		 * @return {@code true} when the bounds are ordered
		 */
		@AssertTrue(message = "the maintenance window must end after it starts")
		public boolean isWindowOrdered() {
			return this.start == null || this.end == null || this.end.isAfter(this.start);
		}

		private static OffsetDateTime parse(String field, String value) {
			if (!StringUtils.hasText(value)) {
				return null;
			}
			try {
				return OffsetDateTime.parse(value.trim());
			}
			catch (DateTimeParseException ex) {
				throw new IllegalArgumentException("'" + value + "' is not a valid '" + field
						+ "' for the Maintenance filter; expected an ISO-8601 date and time carrying its offset,"
						+ " such as 2025-09-01T22:00:00Z or 2025-09-02T00:00:00+02:00");
			}
		}

	}

	/**
	 * A claim value lifting the maintenance: a JSON path pointing into the claims of the
	 * token, and the values that claim may hold.
	 */
	@Validated
	public static class AllowedClaim {

		@NotEmpty
		private String jsonPath;

		@NotEmpty
		private List<@NotEmpty String> values = new ArrayList<>(0);

		@NotNull
		private MatchMode match = MatchMode.ANY;

		/**
		 * Returns the JSON path locating the claim.
		 * @return the json path
		 */
		public String getJsonPath() {
			return this.jsonPath;
		}

		/**
		 * Sets the JSON path locating the claim.
		 * <p>
		 * The path is compiled here rather than on the first request: an expression that
		 * does not parse would otherwise throw once per call, from inside the filter, and
		 * the route would answer the maintenance body to the very population it exempts.
		 * @param jsonPath the json path to set
		 * @throws com.jayway.jsonpath.InvalidPathException when the expression does not
		 * parse
		 */
		public void setJsonPath(String jsonPath) {
			if (StringUtils.hasText(jsonPath)) {
				JsonPath.compile(jsonPath);
			}
			this.jsonPath = jsonPath;
		}

		/**
		 * Returns the values the claim may hold to lift the maintenance.
		 * @return the exempted values
		 */
		public List<String> getValues() {
			return this.values;
		}

		/**
		 * Sets the values the claim may hold to lift the maintenance.
		 * @param values the exempted values
		 */
		public void setValues(List<String> values) {
			this.values = values;
		}

		/**
		 * Returns how the values of this claim are combined.
		 * @return the match mode
		 */
		public MatchMode getMatch() {
			return this.match;
		}

		/**
		 * Sets how the values of this claim are combined: {@link MatchMode#ANY} exempts a
		 * caller whose claim holds one of them, {@link MatchMode#ALL} one whose claim
		 * holds every one.
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
		 * A single satisfied requirement is enough.
		 */
		ANY,

		/**
		 * Every requirement must be satisfied.
		 */
		ALL

	}

}
