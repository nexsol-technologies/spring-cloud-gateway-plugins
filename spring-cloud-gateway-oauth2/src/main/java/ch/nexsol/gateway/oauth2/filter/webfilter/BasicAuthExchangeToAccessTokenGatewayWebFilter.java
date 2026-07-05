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

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;
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

import static ch.nexsol.gateway.oauth2.utils.SecurityUtils.HEADER_AUTHORIZATION_BASIC;

public class BasicAuthExchangeToAccessTokenGatewayWebFilter implements WebFilter, Ordered {

	private static final Logger LOG = LoggerFactory.getLogger(BasicAuthExchangeToAccessTokenGatewayWebFilter.class);

	private static final String HTTPS_SCHEME = "https";

	private static final String HTTP_SCHEME = "http";

	private final BasicAuthExchangeToAccessTokenProperties properties;

	private final Cache tokenCache;

	private final ObservationRegistry registry;

	private final WebClient webClient;

	public BasicAuthExchangeToAccessTokenGatewayWebFilter(BasicAuthExchangeToAccessTokenProperties properties,
			CacheManager cacheManager, ObservationRegistry registry) {
		this.properties = properties;
		this.registry = registry;
		this.webClient = WebClient.builder()
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
			.filter((request, next) -> next.exchange(request)
				.retryWhen(Retry.backoff(3, Duration.ofSeconds(5))
					.doAfterRetry((retry) -> LOG.debug("Retry calling {} : {}", request.url(), retry.totalRetries()))))
			.build();
		this.tokenCache = cacheManager.getCache("basicauth-token-exchange.cache");
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 5;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

		return Mono.deferContextual((contextView) -> {

			ServerHttpRequest request = exchange.getRequest();
			String scheme = request.getURI().getScheme();
			if ((!HTTP_SCHEME.equalsIgnoreCase(scheme) && !HTTPS_SCHEME.equals(scheme))) {
				return chain.filter(exchange);
			}
			String path = request.getPath().value();
			if (path.startsWith("/actuator")) {
				return chain.filter(exchange);
			}

			Observation observation = Observation.createNotStarted("BasicAuthExchange", this.registry)
				.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null))
				.start();

			return Mono.just(request)
				.map((r) -> r.getHeaders())
				.filter((headers) -> this.containsAuthorizationBasic(headers))
				.flatMap((headers) -> Mono.justOrEmpty(this.extractAuthorizationBasic(headers))
					.filter((basicValue) -> this.properties.isUserConfigured(basicValue.getClientId()))
					.flatMap((basicValue) -> {
						LOG.debug("BasicAuth exchange is starting");
						return this.exchangeBasicToJwt(request, headers, basicValue)
							.doOnError((error) -> LOG.error(
									"Exchange basicAuth to access token : error when initate the OAuth 2.0 client credentials flow",
									error));
					}))
				.map((token) -> withBearerAuth(exchange, token))
				.onErrorResume((error) -> Mono.just(exchange))
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

	private BasicValue getUserPasswordFromBasic(String basic) {
		String pair = new String(Base64.getDecoder().decode(basic));
		String[] s = pair.split(":");
		return new BasicValue(s[0], s[1]);
	}

	private boolean containsAuthorizationBasic(HttpHeaders headers) {
		if (headers.containsHeader(HttpHeaders.AUTHORIZATION) && headers.get(HttpHeaders.AUTHORIZATION) != null
				&& !headers.get(HttpHeaders.AUTHORIZATION).isEmpty()) {
			LOG.trace("contains AUTHORIZATION header");
			return headers.get(HttpHeaders.AUTHORIZATION)
				.stream()
				.anyMatch((h) -> h.toLowerCase().startsWith(HEADER_AUTHORIZATION_BASIC));
		}
		LOG.trace("NOT contains AUTHORIZATION header");
		return false;
	}

	private Optional<BasicValue> extractAuthorizationBasic(HttpHeaders headers) {
		if (headers.containsHeader(HttpHeaders.AUTHORIZATION) && headers.get(HttpHeaders.AUTHORIZATION) != null
				&& !headers.get(HttpHeaders.AUTHORIZATION).isEmpty()) {

			return headers.get(HttpHeaders.AUTHORIZATION)
				.stream()
				.map((h) -> h.substring(HEADER_AUTHORIZATION_BASIC.length()))
				.findFirst()
				.map(this::getUserPasswordFromBasic)
				.map((value) -> {
					LOG.trace("BasicValue is " + value.getKey());
					return value;
				})
				.map(Optional::of)
				.orElse(Optional.empty());
		}
		return Optional.empty();
	}

	private Mono<String> exchangeBasicToJwt(ServerHttpRequest request, HttpHeaders headers, BasicValue basicValue) {
		return this.getFromCache(basicValue.getKey())
			.switchIfEmpty(Mono.defer(() -> this.getToken(request, basicValue).doOnNext((newAuthToken) -> {
				if (this.tokenCache != null && newAuthToken != null) {
					try {
						this.tokenCache.put(basicValue.getKey(), newAuthToken);
					}
					catch (Exception ex) {
						LOG.warn("Error on accessing to token cache", ex);
					}
				}
			})));
	}

	private BodyExtractor<Mono<OAuth2AccessTokenResponse>, ReactiveHttpInputMessage> bodyExtractor = OAuth2BodyExtractors
		.oauth2AccessTokenResponse();

	private Mono<String> getToken(ServerHttpRequest request, BasicValue basicValue) {
		return Mono.justOrEmpty(this.properties.getTokenUris().get(basicValue.getClientId()))
			.doOnNext((uri) -> LOG.trace("Call token uri {} ", uri))
			.flatMap((tokenUri) -> this.webClient.post()
				.uri(tokenUri)
				.body(BodyInserters.fromFormData("client_id", basicValue.getClientId())
					.with("client_secret", basicValue.getClientSecret())
					.with("grant_type", AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()))
				.exchangeToMono((response) -> response.body(this.bodyExtractor)
					.map((tokenResponse) -> populateTokenResponse(tokenResponse)))
				.map((accessToken) -> accessToken.getAccessToken().getTokenValue())
				.doOnError((error) -> LOG.error("error when calling token uri {} for client {}", tokenUri,
						basicValue.getClientId(), error)))
			.onErrorResume((ex) -> Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)));

	}

	private OAuth2AccessTokenResponse populateTokenResponse(OAuth2AccessTokenResponse tokenResponse) {
		if (CollectionUtils.isEmpty(tokenResponse.getAccessToken().getScopes())) {
			tokenResponse = OAuth2AccessTokenResponse.withResponse(tokenResponse).build();
		}
		return tokenResponse;
	}

	private ServerWebExchange withBearerAuth(ServerWebExchange exchange, String accessToken) {
		return exchange.mutate().request((r) -> r.headers((headers) -> headers.setBearerAuth(accessToken))).build();
	}

	private Mono<String> getFromCache(String cacheKey) {
		try {
			if (this.tokenCache.get(cacheKey) != null && this.tokenCache.get(cacheKey).get() != null) {
				String tokenExchangeCache = (String) this.tokenCache.get(cacheKey).get();
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

	public static class BasicValue {

		private String clientId;

		private String clientSecret;

		public BasicValue(String clientId, String clientSecret) {
			super();
			this.clientId = clientId;
			this.clientSecret = clientSecret;
		}

		public String getKey() {
			if (this.clientSecret.length() > 5) {
				return this.clientId + ":" + this.clientSecret.substring(0, 5) + "***";
			}
			else {
				return this.clientId + ":***";
			}

		}

		/**
		 * @return the clientId
		 */
		public String getClientId() {
			return this.clientId;
		}

		/**
		 * @param clientId the clientId to set
		 */
		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		/**
		 * @return the clientSecret
		 */
		public String getClientSecret() {
			return this.clientSecret;
		}

		/**
		 * @param clientSecret the clientSecret to set
		 */
		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

	}

}
