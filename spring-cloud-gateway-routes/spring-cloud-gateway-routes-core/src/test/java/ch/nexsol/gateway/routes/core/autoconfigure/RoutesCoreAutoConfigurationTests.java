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

package ch.nexsol.gateway.routes.core.autoconfigure;

import java.util.concurrent.atomic.AtomicInteger;

import ch.nexsol.gateway.routes.core.AbstractRefreshableRouteDefinitionLocator;
import ch.nexsol.gateway.routes.core.RefreshableRouteDefinitionLocatorRefresher;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RoutesCoreAutoConfiguration} and the shared refresher, verifying that
 * a {@link RefreshScopeRefreshedEvent} reloads every refreshable locator in the context.
 */
class RoutesCoreAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(RoutesCoreAutoConfiguration.class));

	@Test
	void refresherIsRegisteredWhenRefreshSupportPresent() {
		this.runner
			.run((context) -> assertThat(context).hasSingleBean(RefreshableRouteDefinitionLocatorRefresher.class));
	}

	@Test
	void refresherIsAbsentWhenRefreshSupportMissing() {
		this.runner.withClassLoader(new FilteredClassLoader(RefreshScopeRefreshedEvent.class))
			.run((context) -> assertThat(context).doesNotHaveBean(RefreshableRouteDefinitionLocatorRefresher.class));
	}

	@Test
	void reloadsEveryLocatorOnRefreshScopeRefreshedEvent() {
		this.runner.withUserConfiguration(LocatorsConfiguration.class).run((context) -> {
			CountingLocator first = context.getBean("firstLocator", CountingLocator.class);
			CountingLocator second = context.getBean("secondLocator", CountingLocator.class);
			assertThat(first.loads()).isZero();
			assertThat(second.loads()).isZero();

			context.publishEvent(new RefreshScopeRefreshedEvent());

			assertThat(first.loads()).isEqualTo(1);
			assertThat(second.loads()).isEqualTo(1);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class LocatorsConfiguration {

		@Bean
		CountingLocator firstLocator(ApplicationEventPublisher publisher) {
			return new CountingLocator(publisher);
		}

		@Bean
		CountingLocator secondLocator(ApplicationEventPublisher publisher) {
			return new CountingLocator(publisher);
		}

	}

	static final class CountingLocator extends AbstractRefreshableRouteDefinitionLocator {

		private final AtomicInteger loads = new AtomicInteger();

		CountingLocator(ApplicationEventPublisher publisher) {
			super(publisher);
		}

		int loads() {
			return this.loads.get();
		}

		@Override
		protected Flux<RouteDefinition> loadRouteDefinitions() {
			this.loads.incrementAndGet();
			return Flux.empty();
		}

	}

}
