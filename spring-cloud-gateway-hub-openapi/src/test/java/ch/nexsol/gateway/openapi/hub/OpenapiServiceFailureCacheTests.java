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
 * Verifies the time an unreachable service may be left alone for. Probing it costs a full
 * timeout, on every refresh, for as long as it stays down: a registry holding many of
 * them makes each refresh wait for all those timeouts. Leaving them alone is asked for,
 * never the default &mdash; by default a service appears as soon as it comes back.
 */
class OpenapiServiceFailureCacheTests {

	private static final String JSON_URL = "http://service-a.example:8080/v3/api-docs.json";

	private static final String YAML_URL = "http://service-a.example:8080/v3/api-docs.yaml";

	private static final String PLAIN_URL = "http://service-a.example:8080/v3/api-docs";

	private final ReactiveDiscoveryClient discoveryClient = mock(ReactiveDiscoveryClient.class);

	private final List<String> probedUrls = new CopyOnWriteArrayList<>();

	private Function<String, Mono<ClientResponse>> exchange = (url) -> Mono
		.error(new IllegalStateException("connection refused"));

	private final WebClient webClient = WebClient.builder().exchangeFunction((request) -> {
		this.probedUrls.add(request.url().toString());
		return this.exchange.apply(request.url().toString());
	}).build();

	@BeforeEach
	void givenInstance() {
		when(this.discoveryClient.getInstances("service-a")).thenReturn(Flux
			.just(new DefaultServiceInstance("service-a-1", "service-a", "service-a.example", 8080, false, Map.of())));
	}

	@Test
	void probesAnUnreachableServiceOnEveryRefreshWhenToldNotToLeaveItAlone() {
		OpenapiService service = service(Duration.ZERO);

		StepVerifier.create(discover(service)).verifyComplete();
		StepVerifier.create(discover(service)).verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, JSON_URL);
	}

	@Test
	void leavesAnUnreachableServiceAloneForTheTimeAskedFor() {
		OpenapiService service = service(Duration.ofSeconds(30));

		StepVerifier.create(discover(service)).verifyComplete();
		StepVerifier.create(discover(service)).verifyComplete();
		StepVerifier.create(discover(service)).verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL);
	}

	@Test
	void probesItAgainOnceThatTimeIsOver() throws InterruptedException {
		OpenapiService service = service(Duration.ofMillis(1));

		StepVerifier.create(discover(service)).verifyComplete();
		Thread.sleep(20);
		this.exchange = (url) -> Mono.just(ClientResponse.create(HttpStatus.OK).body("{}").build());

		StepVerifier.create(discover(service))
			.assertNext((discovered) -> assertThat(discovered.path()).isEqualTo("/v3/api-docs.json"))
			.verifyComplete();
	}

	@Test
	void stillProbesOnEveryRefreshAServiceThatRefusedItsDocument() {
		this.exchange = (url) -> Mono.just(ClientResponse.create(HttpStatus.UNAUTHORIZED).build());
		OpenapiService service = service(Duration.ofSeconds(30));

		StepVerifier.create(discover(service)).verifyComplete();
		StepVerifier.create(discover(service)).verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL, YAML_URL, PLAIN_URL, JSON_URL, YAML_URL, PLAIN_URL);
	}

	@Test
	void leavesAnUnreachableServiceAloneEvenWhenNothingElseIsRemembered() {
		OpenapiService service = new OpenapiService(this.discoveryClient, this.webClient, Duration.ZERO,
				Duration.ofSeconds(30));

		StepVerifier.create(discover(service)).verifyComplete();
		StepVerifier.create(discover(service)).verifyComplete();

		assertThat(this.probedUrls).containsExactly(JSON_URL);
	}

	private OpenapiService service(Duration failureCacheTtl) {
		return new OpenapiService(this.discoveryClient, this.webClient, Duration.ofMinutes(5), failureCacheTtl);
	}

	private static Mono<OpenapiService.OpenapiDiscover> discover(OpenapiService service) {
		return service.discoverOpenapiUrl("service-a", new RouteDefinition());
	}

}
