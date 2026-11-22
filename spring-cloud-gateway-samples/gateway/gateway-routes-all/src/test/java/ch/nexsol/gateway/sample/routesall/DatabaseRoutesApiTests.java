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

package ch.nexsol.gateway.sample.routesall;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Verifies the route the management API has to be able to take: a load-balanced target, a
 * predicate argument holding a backslash, and nothing more than what a route needs.
 */
@SpringBootTest(properties = "spring.cloud.gateway.server.webflux.routes-openapi.enabled=false")
@AutoConfigureWebTestClient
class DatabaseRoutesApiTests {

	@Autowired
	WebTestClient webTestClient;

	@Test
	void shouldStoreAndGiveBackALoadBalancedRouteMatchedOnARegularExpression() {
		String route = """
				{
				"routeId": "orders_by_numeric_tenant",
				"uri": "lb://orders",
				"predicates": [ { "name": "Header", "args": { "header": "X-Tenant", "regexp": "\\\\d+" } } ]
				}
				""";

		this.webTestClient.post()
			.uri("/api/gateway/routes")
			.contentType(MediaType.APPLICATION_JSON)
			.bodyValue(route)
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$.uri")
			.isEqualTo("lb://orders")
			.jsonPath("$.publicRoute")
			.isEqualTo(false);

		this.webTestClient.get()
			.uri("/api/gateway/routes")
			.exchange()
			.expectStatus()
			.isOk()
			.expectBody()
			.jsonPath("$[?(@.routeId == 'orders_by_numeric_tenant')].predicates[0].args.regexp")
			.isEqualTo("\\d+");
	}

}
