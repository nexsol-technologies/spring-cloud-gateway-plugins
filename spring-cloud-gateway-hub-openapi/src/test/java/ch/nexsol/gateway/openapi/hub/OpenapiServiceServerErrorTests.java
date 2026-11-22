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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests what the hub makes of a service answering a server error. A service that is
 * starting, or failing, has not said it has no document: remembering it as one without a
 * document keeps it out of the hub for the whole life of the cache entry, long after it
 * recovered.
 */
class OpenapiServiceServerErrorTests {

	private static final String JSON_URL = "http://service-a.example:8080/v3/api-docs.json";

	private static final String YAML_URL = "http://service-a.example:8080/v3/api-docs.yaml";

	private static final String PLAIN_URL = "http://service-a.example:8080/v3/api-docs";

	private final ReactiveDiscoveryClient discoveryClient = mock(ReactiveDiscoveryClient.class);

	private final List<String> probedUrls = new CopyOnWriteArrayList<>();

	private Function<String, HttpStatus> answer = (url) -> HttpStatus.NOT_FOUND;

	private final WebClient webClient = WebClient.builder().exchangeFunction((request) -> {
		this.probedUrls.add(request.url().toString());
		return Mono.just(ClientResponse.create(this.answer.apply(request.url().toString())).build());
	}).build();

	private final OpenapiService openapiService = new OpenapiService(this.discoveryClient, this.webClient,
			Duration.ofMinutes(5));

	@BeforeEach
	void givenInstance() {
		when(this.discoveryClient.getInstances("service-a")).thenReturn(Flux
			.just(new DefaultServiceInstance("service-a-1", "service-a", "service-a.example", 8080, false, Map.of())));
	}

	@Test
	void aServiceAnsweringServerErrorsIsProbedAgainOnTheNextRefresh() {
		this.answer = (url) -> HttpStatus.SERVICE_UNAVAILABLE;

		StepVerifier.create(discover()).verifyComplete();
		StepVerifier.create(discover()).verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL, PLAIN_URL, JSON_URL, YAML_URL, PLAIN_URL);
	}

	@Test
	void theDocumentIsFoundOnTheRefreshAfterTheServiceRecovered() {
		this.answer = (url) -> HttpStatus.SERVICE_UNAVAILABLE;
		StepVerifier.create(discover()).verifyComplete();

		this.answer = (url) -> url.equals(PLAIN_URL) ? HttpStatus.OK : HttpStatus.NOT_FOUND;

		StepVerifier.create(discover())
			.assertNext((discovered) -> assertThat(discovered.path()).isEqualTo("/v3/api-docs"))
			.verifyComplete();
	}

	@Test
	void aServerErrorOnOnePathIsNotCachedAsAnAbsenceBecauseTheOthersAnsweredNotThere() {
		this.answer = (url) -> url.equals(JSON_URL) ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.NOT_FOUND;

		StepVerifier.create(discover()).verifyComplete();
		StepVerifier.create(discover()).verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL, PLAIN_URL, JSON_URL, YAML_URL, PLAIN_URL);
	}

	@Test
	void probingCarriesOnPastAPathAnsweringAServerError() {
		this.answer = (url) -> url.equals(JSON_URL) ? HttpStatus.INTERNAL_SERVER_ERROR
				: url.equals(YAML_URL) ? HttpStatus.OK : HttpStatus.NOT_FOUND;

		StepVerifier.create(discover())
			.assertNext((discovered) -> assertThat(discovered.path()).isEqualTo("/v3/api-docs.yaml"))
			.verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL);
	}

	@Test
	void anAbsenceEveryPathAnsweredIsStillCached() {
		StepVerifier.create(discover()).verifyComplete();
		StepVerifier.create(discover()).verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL, PLAIN_URL);
	}

	private Mono<OpenapiService.OpenapiDiscover> discover() {
		return this.openapiService.discoverOpenapiUrl("service-a", new RouteDefinition());
	}

}
