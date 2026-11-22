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

package ch.nexsol.gateway.routes.core;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests what an explicit refresh asks of the locators: a reload, which lets a locator
 * keeping something between two reads let go of it, and not the plain refresh a poll
 * runs.
 */
class RefresherReloadTests {

	@Test
	void anExplicitRefreshReloadsTheLocators() {
		KeepingLocator locator = new KeepingLocator();
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("locator", locator);
		RefreshableRouteDefinitionLocatorRefresher refresher = new RefreshableRouteDefinitionLocatorRefresher(
				beans.getBeanProvider(AbstractRefreshableRouteDefinitionLocator.class));

		refresher.onApplicationEvent(new RefreshScopeRefreshedEvent());

		assertThat(locator.reloads).hasValue(1);
		assertThat(locator.reads).hasValue(1);
	}

	@Test
	void aReloadIsARefreshForALocatorKeepingNothing() {
		AtomicInteger reads = new AtomicInteger();
		AbstractRefreshableRouteDefinitionLocator locator = new AbstractRefreshableRouteDefinitionLocator((event) -> {
		}) {
			@Override
			protected Flux<RouteDefinition> loadRouteDefinitions() {
				reads.incrementAndGet();
				return Flux.empty();
			}
		};

		locator.reload().block();

		assertThat(reads).hasValue(1);
	}

	private static final class KeepingLocator extends AbstractRefreshableRouteDefinitionLocator {

		private final AtomicInteger reloads = new AtomicInteger();

		private final AtomicInteger reads = new AtomicInteger();

		KeepingLocator() {
			super((event) -> {
			});
		}

		@Override
		protected Flux<RouteDefinition> loadRouteDefinitions() {
			this.reads.incrementAndGet();
			return Flux.empty();
		}

		@Override
		public Mono<Void> reload() {
			this.reloads.incrementAndGet();
			return super.reload();
		}

	}

}
