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

package ch.nexsol.gateway.openapi.hub;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import ch.nexsol.gateway.openapi.HubOpenapiProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenapiService}.
 */
class OpenapiServiceTests {

	private static final String JSON_URL = "http://service-a.example:8080/v3/api-docs.json";

	private static final String YAML_URL = "http://service-a.example:8080/v3/api-docs.yaml";

	private static final String PLAIN_URL = "http://service-a.example:8080/v3/api-docs";

	private final ReactiveDiscoveryClient discoveryClient = mock(ReactiveDiscoveryClient.class);

	private final List<String> probedUrls = new CopyOnWriteArrayList<>();

	private Function<String, Mono<ClientResponse>> exchange = (url) -> Mono
		.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());

	private final WebClient webClient = WebClient.builder().exchangeFunction((request) -> {
		this.probedUrls.add(request.url().toString());
		return this.exchange.apply(request.url().toString());
	}).build();

	private final OpenapiService openapiService = new OpenapiService(this.discoveryClient, this.webClient,
			Duration.ofMinutes(5));

	@Test
	void completesEmptyWhenTheRouteHasNoInstance() {
		when(this.discoveryClient.getInstances("service-a")).thenReturn(Flux.empty());

		StepVerifier.create(this.openapiService.discoverOpenapiUrl("service-a", new RouteDefinition()))
			.verifyComplete();
	}

	@Test
	void probingStopsAtTheFirstPathServingTheDocument() {
		givenInstance(Map.of());
		this.exchange = (url) -> Mono.just(url.equals(JSON_URL) ? okDocument() : notFound());

		StepVerifier.create(discover())
			.assertNext((discovered) -> assertThat(discovered.path()).isEqualTo("/v3/api-docs.json"))
			.verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL);
	}

	@Test
	void probingFallsBackToTheNextPathWhenTheDocumentIsNotAtTheFirstOne() {
		givenInstance(Map.of());
		this.exchange = (url) -> Mono.just(url.equals(YAML_URL) ? okDocument() : notFound());

		StepVerifier.create(discover())
			.assertNext((discovered) -> assertThat(discovered.path()).isEqualTo("/v3/api-docs.yaml"))
			.verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL);
	}

	@Test
	void theDeclaredMetadataPathBypassesTheProbing() {
		givenInstance(Map.of(OpenapiService.METADATA_SERVICE_INSTANCE_OPENAPI_PATH_KEY, "/openapi/spec"));
		this.exchange = (url) -> Mono.just(okDocument());

		StepVerifier.create(discover())
			.assertNext((discovered) -> assertThat(discovered.path()).isEqualTo("/openapi/spec"))
			.verifyComplete();

		assertThat(this.probedUrls).containsExactly("http://service-a.example:8080/openapi/spec");
	}

	@Test
	void theDocumentIsReleasedInsteadOfBeingBuffered() {
		givenInstance(Map.of());
		AtomicBoolean bodyConsumed = new AtomicBoolean();
		DataBuffer document = DefaultDataBufferFactory.sharedInstance.wrap("{}".getBytes(StandardCharsets.UTF_8));
		this.exchange = (url) -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.body(Flux.just(document).doOnSubscribe((subscription) -> bodyConsumed.set(true)))
			.build());

		StepVerifier.create(discover()).expectNextCount(1).verifyComplete();

		// The connection is only usable again once the body has been drained, but nothing
		// of it is kept: the discovery carries the path, never the document.
		assertThat(bodyConsumed).isTrue();
	}

	@Test
	void theDiscoveredPathIsNotProbedAgainWhileItIsCached() {
		givenInstance(Map.of());
		this.exchange = (url) -> Mono.just(url.equals(PLAIN_URL) ? okDocument() : notFound());

		StepVerifier.create(discover()).expectNextCount(1).verifyComplete();
		StepVerifier.create(discover())
			.assertNext((discovered) -> assertThat(discovered.path()).isEqualTo("/v3/api-docs"))
			.verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL, PLAIN_URL);
	}

	@Test
	void theAbsenceOfDocumentIsCachedWhenTheServiceAnsweredForEveryPath() {
		givenInstance(Map.of());
		this.exchange = (url) -> Mono.just(notFound());

		StepVerifier.create(discover()).verifyComplete();
		StepVerifier.create(discover()).verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL, PLAIN_URL);
	}

	@Test
	void anUnreachableServiceIsProbedAgainOnTheNextRefresh() {
		givenInstance(Map.of());
		this.exchange = (url) -> Mono.error(new IllegalStateException("connection refused"));

		StepVerifier.create(discover()).verifyComplete();
		StepVerifier.create(discover()).verifyComplete();

		// Failing to reach a service says nothing about its document: caching that would
		// keep it out of the hub long after it came back.
		assertThat(this.probedUrls).containsExactly(JSON_URL, JSON_URL);
	}

	@Test
	void probingStopsAtTheFirstPathTheInstanceCouldNotBeReachedOn() {
		givenInstance(Map.of());
		this.exchange = (url) -> Mono.error(new IllegalStateException("connection refused"));

		StepVerifier.create(discover()).verifyComplete();

		// The remaining paths lead to the same instance and would fail the same way, each
		// costing another full timeout. With a registry holding hundreds of services,
		// that is the difference between one timeout per unreachable service and one per
		// candidate path.
		assertThat(this.probedUrls).containsExactly(JSON_URL);
	}

	@Test
	void aPathTheInstanceAnsweredForDoesNotStopTheProbing() {
		givenInstance(Map.of());
		this.exchange = (url) -> Mono.just(url.equals(PLAIN_URL) ? okDocument() : notFound());

		StepVerifier.create(discover())
			.assertNext((discovered) -> assertThat(discovered.path()).isEqualTo("/v3/api-docs"))
			.verifyComplete();

		// A 404 is an answer: the instance is reachable and the next path is worth
		// trying.
		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL, PLAIN_URL);
	}

	@Test
	void everyRefreshProbesAgainWhenTheCacheIsDisabled() {
		givenInstance(Map.of());
		this.exchange = (url) -> Mono.just(url.equals(JSON_URL) ? okDocument() : notFound());
		OpenapiService withoutCache = new OpenapiService(this.discoveryClient, this.webClient, Duration.ZERO);

		StepVerifier.create(withoutCache.discoverOpenapiUrl("service-a", new RouteDefinition()))
			.expectNextCount(1)
			.verifyComplete();
		StepVerifier.create(withoutCache.discoverOpenapiUrl("service-a", new RouteDefinition()))
			.expectNextCount(1)
			.verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, JSON_URL);
	}

	@Test
	void aServiceThatNeverAnswersGivesUpOnTheConfiguredTimeout() {
		DisposableServer silentService = HttpServer.create()
			.host("localhost")
			.port(0)
			.handle((request, response) -> Mono.never())
			.bindNow();
		HubOpenapiProperties.Discovery properties = new HubOpenapiProperties.Discovery();
		properties.setTimeout(Duration.ofMillis(200));
		OpenapiService service = new OpenapiService(this.discoveryClient, properties);
		when(this.discoveryClient.getInstances("service-a"))
			.thenReturn(Flux.just(new DefaultServiceInstance("service-a-1", "service-a", silentService.host(),
					silentService.port(), false)));

		try {
			StepVerifier.create(service.discoverOpenapiUrl("service-a", new RouteDefinition()))
				.expectComplete()
				.verify(Duration.ofSeconds(5));
		}
		finally {
			service.destroy();
			silentService.disposeNow();
		}
	}

	private void givenInstance(Map<String, String> metadata) {
		when(this.discoveryClient.getInstances("service-a")).thenReturn(Flux
			.just(new DefaultServiceInstance("service-a-1", "service-a", "service-a.example", 8080, false, metadata)));
	}

	private Mono<OpenapiService.OpenapiDiscover> discover() {
		return this.openapiService.discoverOpenapiUrl("service-a", new RouteDefinition());
	}

	private ClientResponse okDocument() {
		return ClientResponse.create(HttpStatus.OK).body("{\"openapi\":\"3.0.1\"}").build();
	}

	private ClientResponse notFound() {
		return ClientResponse.create(HttpStatus.NOT_FOUND).build();
	}

}
