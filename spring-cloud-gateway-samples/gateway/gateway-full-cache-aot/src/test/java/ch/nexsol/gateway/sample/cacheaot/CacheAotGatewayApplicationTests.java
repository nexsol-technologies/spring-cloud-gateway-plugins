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

package ch.nexsol.gateway.sample.cacheaot;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CacheAotGatewayApplication}.
 * <p>
 * Every route of this sample carries a filter whose arguments are bound reflectively, and
 * the built routes are what is asserted, not the definitions: a route whose arguments do
 * not bind is never built. On the JVM the binding is live reflection and no hint is read,
 * so this says the sample's configuration is sound; whether the hints are complete is
 * what the native workflow says.
 */
@SpringBootTest
class CacheAotGatewayApplicationTests {

	@Autowired
	private RouteLocator routeLocator;

	@Test
	void buildsEveryRouteOfTheSample() {
		assertThat(this.routeLocator.getRoutes().map(Route::getId).collectList().block()).contains("authorization",
				"convert-http-method", "maintenance", "authorization-token", "audited", "bookstore",
				"files_httpbin_route");
	}

}
