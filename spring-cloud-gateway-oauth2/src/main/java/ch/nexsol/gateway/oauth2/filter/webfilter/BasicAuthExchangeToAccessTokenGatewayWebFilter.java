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

package ch.nexsol.gateway.oauth2.filter.webfilter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;
import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties.Client;
import ch.nexsol.gateway.oauth2.utils.SecurityUtils;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTParser;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpInputMessage;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.web.reactive.function.OAuth2BodyExtractors;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.BodyExtractor;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriComponentsBuilder;

import static ch.nexsol.gateway.oauth2.utils.SecurityUtils.HEADER_AUTHORIZATION_BASIC;

/**
 * {@link WebFilter} that intercepts requests carrying a Basic {@code Authorization}
 * header for a configured client and exchanges the client credentials for an OAuth 2.0
 * access token using the client credentials grant. The resulting bearer token is cached
 * (keyed on the client id and a hash of the secret) and set on the forwarded request.
 */
public class BasicAuthExchangeToAccessTokenGatewayWebFilter implements WebFilter, Ordered {

	/**
	 * Attribute set on an exchange whose Basic credentials were exchanged for an access
	 * token, holding the client id they named.
	 * <p>
	 * This is what the security chain of the plugin matches on. Matching the Basic
	 * {@code Authorization} header instead cannot work: this filter is a
	 * {@link WebFilter} bean, so it is registered globally and runs at
	 * {@link Ordered#HIGHEST_PRECEDENCE} {@code + 5}, well ahead of the
	 * {@code WebFilterChainProxy} at {@code -100}. By the time Spring Security evaluates
	 * its matchers the header is already a bearer one. Matching the attribute also means
	 * the chain accepts only what this filter actually authorized, never a request that
	 * merely names a configured client.
	 */
	public static final String EXCHANGED_CLIENT_ATTRIBUTE = BasicAuthExchangeToAccessTokenGatewayWebFilter.class
		.getName() + ".exchangedClient";

	private static final Logger LOG = LoggerFactory.getLogger(BasicAuthExchangeToAccessTokenGatewayWebFilter.class);

	private final BasicAuthExchangeToAccessTokenProperties properties;

	private final Cache tokenCache;

	private final ObservationRegistry registry;

	private final WebClient webClient;

	// Coalesces concurrent token exchanges for the same client so a cache miss triggers a
	// single call to the OAuth server instead of one per in-flight request.
	private final ConcurrentHashMap<String, Mono<String>> inFlightExchanges = new ConcurrentHashMap<>();

	/**
	 * Create a new filter.
	 * <p>
	 * The client is derived from the application {@code WebClient.Builder} rather than
	 * built from scratch, so every {@code WebClientCustomizer} the application configured
	 * applies to it &mdash; the connector and its timeouts, the codecs, and the
	 * observation registry. That last one is the reason this matters here: without it the
	 * exchange with the authorization server carries no client span and does not
	 * propagate the trace context, which makes an authentication hop invisible in a
	 * gateway that traces everything else. The builder Spring Boot auto-configures is
	 * prototype scoped, so configuring it here affects nothing else.
	 * @param properties the Basic-auth to access-token exchange configuration
	 * @param cacheManager the cache manager providing the token exchange cache
	 * @param registry the observation registry used to instrument the exchange
	 * @param webClientBuilder the application web client builder
	 */
	public BasicAuthExchangeToAccessTokenGatewayWebFilter(BasicAuthExchangeToAccessTokenProperties properties,
			CacheManager cacheManager, ObservationRegistry registry, WebClient.Builder webClientBuilder) {
		this.properties = properties;
		this.registry = registry;
		this.webClient = webClientBuilder
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
			.filter((request, next) -> next.exchange(request)
				.retryWhen(Retry.backoff(3, Duration.ofSeconds(5))
					.doAfterRetry((retry) -> LOG.debug("Retry calling {} : {}", request.url(), retry.totalRetries()))))
			.build();
		this.tokenCache = cacheManager.getCache("basicauth-token-exchange.cache");
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 5;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

		return Mono.deferContextual((contextView) -> {

			ServerHttpRequest request = exchange.getRequest();
			if (!SecurityUtils.isCandidateForExchange(request)) {
				return chain.filter(exchange);
			}

			Observation observation = Observation.createNotStarted("BasicAuthExchange", this.registry)
				.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null))
				.start();

			// An exchange that fails is denied, never forwarded: letting the request
			// through would send the raw Basic credentials to a downstream service that
			// expects a bearer token, and would turn a refused credential or an
			// unreachable authorization server into a request served as if it had been
			// authorized. Requests this filter has nothing to do with — no Basic
			// credentials, or a client id it is not configured for — complete empty and
			// are forwarded untouched.
			return Mono.justOrEmpty(SecurityUtils.resolveBasicValue(request, this.properties))
				.filter((basicValue) -> this.properties.isUserConfigured(basicValue.getClientId()))
				.flatMap((basicValue) -> {
					LOG.debug("BasicAuth exchange is starting");
					return this.exchangeBasicToJwt(basicValue)
						.doOnError((error) -> LOG.error(
								"Exchange basicAuth to access token : error when initate the OAuth 2.0 client credentials flow",
								error))
						.map((token) -> withBearerAuth(exchange, basicValue, token));
				})
				.defaultIfEmpty(exchange)
				.doOnSuccess((result) -> observation.stop())
				.doOnCancel(observation::stop)
				.doOnError((t) -> {
					observation.error(t);
					observation.stop();
				})
				.flatMap(chain::filter);
		});

	}

	private Mono<String> exchangeBasicToJwt(BasicValue basicValue) {
		String key = basicValue.getKey();
		return this.getFromCache(key)
			.switchIfEmpty(Mono.defer(() -> this.inFlightExchanges.computeIfAbsent(key,
					(k) -> this.getToken(basicValue).doOnNext((newAuthToken) -> {
						if (this.tokenCache != null && newAuthToken != null) {
							try {
								this.tokenCache.put(k, newAuthToken);
							}
							catch (Exception ex) {
								LOG.warn("Error on accessing to token cache", ex);
							}
						}
					}).doFinally((signal) -> this.inFlightExchanges.remove(k)).cache())));
	}

	private BodyExtractor<Mono<OAuth2AccessTokenResponse>, ReactiveHttpInputMessage> bodyExtractor = OAuth2BodyExtractors
		.oauth2AccessTokenResponse();

	private Mono<String> getToken(BasicValue basicValue) {
		return Mono.justOrEmpty(this.properties.resolveClient(basicValue.getClientId()))
			.doOnNext((client) -> LOG.trace("Call token uri {} ", client.getTokenUri()))
			.flatMap((client) -> this.webClient.post()
				.uri(client.getTokenUri())
				.body(tokenRequest(basicValue, client))
				.exchangeToMono((response) -> response.body(this.bodyExtractor)
					.map((tokenResponse) -> populateTokenResponse(tokenResponse)))
				.map((accessToken) -> accessToken.getAccessToken().getTokenValue())
				.doOnError((error) -> LOG.error("error when calling token uri {} for client {}", client.getTokenUri(),
						basicValue.getClientId(), error)))
			.onErrorResume((ex) -> Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)));

	}

	private BodyInserters.FormInserter<String> tokenRequest(BasicValue basicValue, Client client) {
		BodyInserters.FormInserter<String> form = BodyInserters.fromFormData("client_id", basicValue.getClientId())
			.with("client_secret", basicValue.getClientSecret())
			.with("grant_type", AuthorizationGrantType.CLIENT_CREDENTIALS.getValue());
		// An empty `scope` is not the same as no `scope` at all: an authorization server
		// answers the former with invalid_scope where it would have granted the client
		// its default scopes for the latter.
		if (!client.getScopes().isEmpty()) {
			form = form.with("scope", String.join(" ", client.getScopes()));
		}
		return form;
	}

	private OAuth2AccessTokenResponse populateTokenResponse(OAuth2AccessTokenResponse tokenResponse) {
		if (CollectionUtils.isEmpty(tokenResponse.getAccessToken().getScopes())) {
			tokenResponse = OAuth2AccessTokenResponse.withResponse(tokenResponse).build();
		}
		return tokenResponse;
	}

	private ServerWebExchange withBearerAuth(ServerWebExchange exchange, BasicValue basicValue, String accessToken) {
		// Read by the security chain of the plugin, which lets an exchanged request
		// through. Set on the exchange rather than the mutated one on purpose: a mutated
		// exchange shares the attributes of the exchange it wraps, and this must be
		// visible to whatever runs next.
		exchange.getAttributes().put(EXCHANGED_CLIENT_ATTRIBUTE, basicValue.getClientId());
		return exchange.mutate().request((builder) -> {
			builder.headers((headers) -> headers.setBearerAuth(accessToken));
			stripCredentialsQueryParam(exchange.getRequest(), builder);
		}).build();
	}

	/**
	 * Drop the credentials query parameter from the request forwarded downstream. The
	 * whole point of the exchange is that the credentials stop here; leaving them in the
	 * URL would hand them to the downstream service, its access log and its {@code
	 * Referer} headers.
	 * @param request the incoming request
	 * @param builder the builder of the forwarded request
	 */
	private void stripCredentialsQueryParam(ServerHttpRequest request, ServerHttpRequest.Builder builder) {
		String name = this.properties.getCredentialsQueryParamName();
		if (!this.properties.isCredentialsInQueryParam() || !request.getQueryParams().containsKey(name)) {
			return;
		}
		builder.uri(UriComponentsBuilder.fromUri(request.getURI()).replaceQueryParam(name).build(true).toUri());
	}

	private Mono<String> getFromCache(String cacheKey) {
		try {
			Cache.ValueWrapper wrapper = this.tokenCache.get(cacheKey);
			if (wrapper != null && wrapper.get() != null) {
				String tokenExchangeCache = (String) wrapper.get();
				LOG.debug("token found in cache for key [{}]", cacheKey);
				if (isTokenAvailable(tokenExchangeCache)) {
					return Mono.just(tokenExchangeCache);
				}
				else {
					LOG.debug("token is expired");
					this.tokenCache.evict(cacheKey);
				}
			}
		}
		catch (Exception ex) {
			LOG.warn("Error on accessing to token cache", ex);
		}
		return Mono.empty();
	}

	private boolean isTokenAvailable(String token) {
		JWT jwt = parse(token);
		JWTClaimsSet claims = null;
		try {
			claims = jwt.getJWTClaimsSet();
			if (claims.getExpirationTime() != null) {
				Instant now = Instant.now().plusSeconds(30);
				LOG.debug("token Expiration Time {} / now {} ", claims.getExpirationTime().toInstant(), now);
				return claims.getExpirationTime().toInstant().isAfter(now);
			}
			return false;
		}
		catch (ParseException ex) {
			LOG.error("claims parsing error", ex);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					"Not Authorized to access to this internal resource due to an internal error");
		}
	}

	private JWT parse(String token) {
		try {
			return JWTParser.parse(token);
		}
		catch (ParseException ex) {
			LOG.error("error when parsing token", ex);
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
					"Not Authorized to access to this internal resource due to an internal error");
		}
	}

	/**
	 * Holder for the client id / client secret pair decoded from a Basic {@code
	 * Authorization} header.
	 */
	public static class BasicValue {

		private final String clientId;

		private final String clientSecret;

		/**
		 * Create a new {@code BasicValue}.
		 * @param clientId the OAuth 2.0 client id
		 * @param clientSecret the OAuth 2.0 client secret
		 */
		public BasicValue(String clientId, String clientSecret) {
			super();
			this.clientId = clientId;
			this.clientSecret = clientSecret;
		}

		/**
		 * Whether the given {@code Authorization} header value announces Basic
		 * credentials. The scheme is case-insensitive per RFC 7235, and the comparison is
		 * made in {@link Locale#ROOT} so it does not depend on the locale of the JVM.
		 * @param header the raw header value
		 * @return {@code true} when the header carries Basic credentials
		 */
		public static boolean isBasic(String header) {
			return header != null && header.toLowerCase(Locale.ROOT).startsWith(HEADER_AUTHORIZATION_BASIC);
		}

		/**
		 * Decodes a Basic {@code Authorization} header into its client id / client secret
		 * pair.
		 * <p>
		 * Everything about the header is attacker-controlled, so nothing here may throw:
		 * a value that is not valid Base64, or that carries no {@code :} separator,
		 * yields an empty result and the request is simply left alone. The pair is split
		 * on the <em>first</em> separator only, since RFC 7617 forbids a colon in the
		 * user id but allows one in the password.
		 * @param header the raw header value
		 * @return the decoded pair, or empty when the header is not a usable one
		 */
		public static Optional<BasicValue> parse(String header) {
			return parseCredentials(header.substring(HEADER_AUTHORIZATION_BASIC.length()));
		}

		/**
		 * Decodes the Base64 {@code client-id:client-secret} pair a Basic
		 * {@code Authorization} header or a credentials query parameter carries, without
		 * its scheme prefix. Same defensive contract as {@link #parse(String)}.
		 * @param credentials the Base64 encoded pair
		 * @return the decoded pair, or empty when the value is not a usable one
		 */
		public static Optional<BasicValue> parseCredentials(String credentials) {
			byte[] decoded;
			try {
				decoded = Base64.getDecoder().decode(credentials);
			}
			catch (IllegalArgumentException ex) {
				LOG.debug("Ignoring Basic credentials that are not valid Base64");
				return Optional.empty();
			}
			String pair = new String(decoded, StandardCharsets.UTF_8);
			int separator = pair.indexOf(':');
			if (separator < 0) {
				LOG.debug("Ignoring Basic credentials carrying no ':' separator");
				return Optional.empty();
			}
			return Optional.of(new BasicValue(pair.substring(0, separator), pair.substring(separator + 1)));
		}

		/**
		 * Build a stable, collision-safe key that also doubles as a safe log token. The
		 * key combines the client id with the SHA-256 hex digest of the full client
		 * secret, so it is unique per secret (no truncation collisions or stale tokens
		 * after a rotation sharing a prefix) yet non-reversible for logging.
		 * @return the cache key / safe log token for this pair
		 */
		public String getKey() {
			return this.clientId + ":" + sha256Hex(this.clientSecret);
		}

		/**
		 * Return the OAuth 2.0 client id.
		 * @return the clientId
		 */
		public String getClientId() {
			return this.clientId;
		}

		/**
		 * Return the OAuth 2.0 client secret.
		 * @return the clientSecret
		 */
		public String getClientSecret() {
			return this.clientSecret;
		}

		private static String sha256Hex(String value) {
			try {
				MessageDigest digest = MessageDigest.getInstance("SHA-256");
				byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
				StringBuilder hex = new StringBuilder(hash.length * 2);
				for (byte b : hash) {
					hex.append(Character.forDigit((b >> 4) & 0xF, 16));
					hex.append(Character.forDigit(b & 0xF, 16));
				}
				return hex.toString();
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException("SHA-256 algorithm not available", ex);
			}
		}

	}

}
