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

package ch.nexsol.gateway.sample;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is worth asserting on the sample running every plugin at once is that they
 * coexist: the context starts with all of them on the classpath, the sources aggregate,
 * and the shell serves the views they light up.
 * <p>
 * The sources reaching outside are turned off — a contract fetched over the network and a
 * service registry are what the sample needs when it is run, not when it is tested.
 */
@SpringBootTest(properties = { "eureka.client.enabled=false",
		"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
@AutoConfigureWebTestClient
class GatewayApplicationTests {

	@Autowired
	RouteDefinitionLocator routeDefinitionLocator;

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldAggregateThePropertiesAndTheFileSources() {
		StepVerifier.create(this.routeDefinitionLocator.getRouteDefinitions().map(RouteDefinition::getId).collectList())
			.assertNext((ids) -> assertThat(ids).contains("test-authorization", "files_httpbin_route"))
			.verifyComplete();
	}

	@Test
	void shouldServeTheViewsEveryPluginLightsUp() {
		this.webTestClient.get().uri("/ui").exchange().expectStatus().isOk();
		this.webTestClient.get().uri("/ui/routes").exchange().expectStatus().isOk();
		this.webTestClient.get().uri("/ui/routes/db").exchange().expectStatus().isOk();
		this.webTestClient.get().uri("/ui/metrics").exchange().expectStatus().isOk();
		this.webTestClient.get().uri("/ui/audit").exchange().expectStatus().isOk();
	}

}
