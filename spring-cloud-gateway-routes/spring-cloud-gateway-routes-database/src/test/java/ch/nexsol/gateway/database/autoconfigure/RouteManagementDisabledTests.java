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

package ch.nexsol.gateway.database.autoconfigure;

import ch.nexsol.gateway.database.controller.RouteController;
import ch.nexsol.gateway.database.locator.DatabaseRouteDefinitionLocator;
import ch.nexsol.gateway.dbwiring.AutoConfiguredApplication;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@code enabled=false}: the plugin is not wired at all, source included. This is
 * the switch {@code access} is not &mdash; that one keeps the database feeding the
 * gateway and only decides what is published.
 */
@SpringBootTest(classes = AutoConfiguredApplication.class,
		properties = "spring.cloud.gateway.server.webflux.routes-database.enabled=false")
class RouteManagementDisabledTests {

	@Autowired
	ApplicationContext context;

	@Test
	void shouldStopFeedingTheGatewayItsRoutes() {
		assertThat(this.context.getBeansOfType(DatabaseRouteDefinitionLocator.class)).isEmpty();
	}

	@Test
	void shouldRegisterNoEndpoint() {
		assertThat(this.context.getBeansOfType(RouteController.class)).isEmpty();
		assertThat(this.context.containsBean("routeApiSecuredPaths")).isFalse();
	}

	@Test
	void shouldContributeNoSecurityChainForWhatItNoLongerServes() {
		// Closing paths nobody answers would turn a 404 into a 401.
		assertThat(this.context.containsBean("routeManagementSecurityWebFilterChain")).isFalse();
	}

}
