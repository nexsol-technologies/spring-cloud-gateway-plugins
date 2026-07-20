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

package ch.nexsol.gateway.routes.files;

import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FileRouteDefinitionLocator} verifying caching and refresh event
 * publication on top of the file loader.
 */
class FileRouteDefinitionLocatorTests {

	private ApplicationEvent lastEvent;

	private final ApplicationEventPublisher publisher = (event) -> this.lastEvent = (ApplicationEvent) event;

	private FileRouteDefinitionLocator locator() {
		FileRouteDefinitionLoader loader = new FileRouteDefinitionLoader(new RouteDefinitionFileParser(),
				List.of("classpath:routes/sample-routes.yaml"), new PathMatchingResourcePatternResolver());
		return new FileRouteDefinitionLocator(loader, this.publisher);
	}

	@Test
	void servesRoutesAfterRefreshAndPublishesEvent() {
		FileRouteDefinitionLocator locator = locator();

		StepVerifier.create(locator.getRouteDefinitions()).verifyComplete();

		StepVerifier.create(locator.refresh()).verifyComplete();

		StepVerifier.create(locator.getRouteDefinitions().map(RouteDefinition::getId))
			.expectNext("after_route")
			.verifyComplete();
		assertThat(this.lastEvent).isInstanceOf(RefreshRoutesEvent.class);
	}

}
