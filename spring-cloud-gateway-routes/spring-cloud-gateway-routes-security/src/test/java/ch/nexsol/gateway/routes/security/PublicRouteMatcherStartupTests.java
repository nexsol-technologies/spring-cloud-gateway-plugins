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

package ch.nexsol.gateway.routes.security;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the gateway starts when a source of routes publishes an event while it is
 * being built, which is what the database of the routes does.
 * <p>
 * The matcher listens to the route refreshes, and a listener is instantiated as soon as
 * any bean publishes an event. Should it need the route locator at that moment, it asks
 * for a bean that is still waiting for the very source publishing the event, and the
 * context fails on a circular reference.
 */
@SpringBootTest(classes = PublicRouteMatcherStartupTests.Application.class)
class PublicRouteMatcherStartupTests {

	@Autowired
	PublicRouteMatcher matcher;

	@Test
	void startsWhenARouteSourcePublishesAnEventWhileItIsBuilt() {
		assertThat(this.matcher).isNotNull();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class Application {

		@Bean
		RouteDefinitionLocator publishingRouteSource(ApplicationEventPublisher publisher) {
			publisher.publishEvent("a source of routes is starting");
			return Flux::empty;
		}

	}

}
