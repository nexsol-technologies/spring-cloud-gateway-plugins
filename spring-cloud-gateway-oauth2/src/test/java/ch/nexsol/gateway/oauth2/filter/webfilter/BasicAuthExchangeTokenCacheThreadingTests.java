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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import ch.nexsol.gateway.oauth2.filter.webfilter.BasicAuthExchangeToAccessTokenGatewayWebFilter.BasicValue;
import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.micrometer.observation.ObservationRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the thread the token cache is reached on. The cache is the one of the
 * application, and nothing says it answers from memory: backed by Redis, reading it or
 * writing it is a network round-trip made with a blocking call. Made on the thread that
 * serves the request, it holds up every other request that thread serves.
 * <p>
 * Each request is subscribed from a thread that must not block, the way the server
 * subscribes it, and every cache records whether it was called on such a thread.
 */
class BasicAuthExchangeTokenCacheThreadingTests {

	private static final String PREFIX = "exchange";

	private static final String CLIENT_ID = "my-client";

	private static final String SECRET = "my-secret";

	private static final String CACHE_NAME = "basicauth-token-exchange.cache";

	private static final String KEY = new BasicValue(CLIENT_ID, SECRET).getKey();

	private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

	private final WebFilterChain chain = (exchange) -> {
		this.forwarded.set(exchange);
		return Mono.empty();
	};

	private MockWebServer authorizationServer;

	@BeforeEach
	void startAuthorizationServer() throws IOException {
		this.authorizationServer = new MockWebServer();
		this.authorizationServer.start();
	}

	@AfterEach
	void stopAuthorizationServer() throws IOException {
		this.authorizationServer.shutdown();
	}

	@Test
	void readsARemoteCacheOffTheThreadsThatMustNotBlock() {
		RemoteCache cache = new RemoteCache();
		String token = token(Duration.ofHours(1));
		cache.entries.put(KEY, token);

		serve(filter(cache));

		assertThat(bearer()).isEqualTo("Bearer " + token);
		assertThat(this.authorizationServer.getRequestCount()).isZero();
		assertThat(cache.reads).isNotEmpty().doesNotContain(true);
	}

	@Test
	void writesARemoteCacheOffTheThreadsThatMustNotBlock() {
		RemoteCache cache = new RemoteCache();
		this.authorizationServer.enqueue(tokenResponse(token(Duration.ofHours(1))));

		serve(filter(cache));

		assertThat(bearer()).startsWith("Bearer ");
		assertThat(cache.entries).containsKey(KEY);
		assertThat(cache.writes).hasSize(1).doesNotContain(true);
	}

	@Test
	void evictsFromARemoteCacheOffTheThreadsThatMustNotBlock() {
		RemoteCache cache = new RemoteCache();
		cache.entries.put(KEY, token(Duration.ofHours(-1)));
		String fresh = token(Duration.ofHours(1));
		this.authorizationServer.enqueue(tokenResponse(fresh));

		serve(filter(cache));

		assertThat(bearer()).isEqualTo("Bearer " + fresh);
		assertThat(this.authorizationServer.getRequestCount()).isEqualTo(1);
		assertThat(cache.evictions).hasSize(1).doesNotContain(true);
		assertThat(cache.entries).containsEntry(KEY, fresh);
	}

	@Test
	void exchangesOnceForTheRequestsSharingARemoteCache() {
		RemoteCache cache = new RemoteCache();
		this.authorizationServer.enqueue(tokenResponse(token(Duration.ofHours(1))));
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(cache);

		serve(filter);
		String first = bearer();
		serve(filter);

		assertThat(bearer()).isEqualTo(first);
		assertThat(this.authorizationServer.getRequestCount()).isEqualTo(1);
	}

	@Test
	void aRemoteCacheThatFailsDoesNotFailTheRequest() {
		RemoteCache cache = new RemoteCache();
		cache.failing = true;
		String token = token(Duration.ofHours(1));
		this.authorizationServer.enqueue(tokenResponse(token));

		serve(filter(cache));

		assertThat(bearer()).isEqualTo("Bearer " + token);
		assertThat(cache.reads).doesNotContain(true);
		assertThat(cache.writes).doesNotContain(true);
	}

	@Test
	void aCacheHeldInMemoryIsStillReadAndWrittenWhereTheRequestIs() {
		InMemoryCache cache = new InMemoryCache();
		this.authorizationServer.enqueue(tokenResponse(token(Duration.ofHours(1))));
		BasicAuthExchangeToAccessTokenGatewayWebFilter filter = filter(cache);

		serve(filter);
		serve(filter);

		assertThat(this.authorizationServer.getRequestCount()).isEqualTo(1);
		// No hop to a thread of its own for a cache that only reads a map.
		assertThat(cache.threads).isNotEmpty().noneMatch((name) -> name.startsWith("boundedElastic"));
	}

	/**
	 * Serves one request the way the server does: subscribed from a thread that must not
	 * block.
	 */
	private void serve(BasicAuthExchangeToAccessTokenGatewayWebFilter filter) {
		Mono.defer(() -> filter.filter(request(), this.chain))
			.subscribeOn(Schedulers.parallel())
			.block(Duration.ofSeconds(10));
	}

	private String bearer() {
		return this.forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
	}

	private BasicAuthExchangeToAccessTokenGatewayWebFilter filter(Cache cache) {
		Map<String, String> source = Map.of(PREFIX + ".token-uris." + CLIENT_ID,
				this.authorizationServer.url("/oauth/token").toString());
		BasicAuthExchangeToAccessTokenProperties properties = new Binder(new MapConfigurationPropertySource(source))
			.bind(PREFIX, BasicAuthExchangeToAccessTokenProperties.class)
			.get();
		CacheManager cacheManager = new CacheManager() {
			@Override
			public Cache getCache(String name) {
				return CACHE_NAME.equals(name) ? cache : null;
			}

			@Override
			public Collection<String> getCacheNames() {
				return List.of(CACHE_NAME);
			}
		};
		return new BasicAuthExchangeToAccessTokenGatewayWebFilter(properties, cacheManager, ObservationRegistry.NOOP,
				WebClient.builder());
	}

	private static MockServerWebExchange request() {
		String credentials = Base64.getEncoder().encodeToString((CLIENT_ID + ":" + SECRET).getBytes());
		return MockServerWebExchange.from(MockServerHttpRequest.get("http://gateway.example/api/resource")
			.header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
			.build());
	}

	private static String token(Duration lifetime) {
		return new PlainJWT(new JWTClaimsSet.Builder().expirationTime(Date.from(Instant.now().plus(lifetime))).build())
			.serialize();
	}

	private static MockResponse tokenResponse(String accessToken) {
		return new MockResponse().setResponseCode(200)
			.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.setBody("{\"access_token\": \"" + accessToken + "\", \"token_type\": \"Bearer\", \"expires_in\": 3600}");
	}

	/**
	 * A cache standing in for one reached over the network: every call takes a while, and
	 * says whether the thread it was made on is one that must not block.
	 */
	public static class RemoteCache implements Cache {

		final Map<Object, Object> entries = new ConcurrentHashMap<>();

		final List<Boolean> reads = new CopyOnWriteArrayList<>();

		final List<Boolean> writes = new CopyOnWriteArrayList<>();

		final List<Boolean> evictions = new CopyOnWriteArrayList<>();

		volatile boolean failing;

		@Override
		public String getName() {
			return CACHE_NAME;
		}

		@Override
		public Object getNativeCache() {
			return this.entries;
		}

		@Override
		public ValueWrapper get(Object key) {
			roundTrip(this.reads);
			Object value = this.entries.get(key);
			return (value != null) ? new SimpleValueWrapper(value) : null;
		}

		@Override
		public <T> T get(Object key, Class<T> type) {
			roundTrip(this.reads);
			return type.cast(this.entries.get(key));
		}

		@Override
		public <T> T get(Object key, Callable<T> valueLoader) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void put(Object key, Object value) {
			roundTrip(this.writes);
			this.entries.put(key, value);
		}

		@Override
		public void evict(Object key) {
			roundTrip(this.evictions);
			this.entries.remove(key);
		}

		@Override
		public void clear() {
			this.entries.clear();
		}

		private void roundTrip(List<Boolean> calls) {
			calls.add(Schedulers.isInNonBlockingThread());
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			if (this.failing) {
				throw new IllegalStateException("the cache is out of reach");
			}
		}

	}

	/**
	 * The cache an application has when it declares none of its own, recording the thread
	 * of every call.
	 */
	public static class InMemoryCache extends ConcurrentMapCache {

		final List<String> threads = new CopyOnWriteArrayList<>();

		InMemoryCache() {
			super(CACHE_NAME);
		}

		@Override
		public ValueWrapper get(Object key) {
			this.threads.add(Thread.currentThread().getName());
			return super.get(key);
		}

		@Override
		public void put(Object key, Object value) {
			this.threads.add(Thread.currentThread().getName());
			super.put(key, value);
		}

	}

}
