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

import reactor.core.publisher.Flux;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Re-loads every {@link AbstractRefreshableRouteDefinitionLocator} in the context when a
 * {@link RefreshScopeRefreshedEvent} is published, so that {@code /actuator/refresh} and
 * {@code /actuator/busrefresh} pull the latest route definitions from their source
 * instead of rebuilding the gateway from the cached snapshot.
 * <p>
 * The locators are resolved lazily at event time, so this listener works regardless of
 * the order in which the individual route source auto-configurations are applied, and is
 * a no-op when no refreshable locator is present.
 */
public class RefreshableRouteDefinitionLocatorRefresher implements ApplicationListener<RefreshScopeRefreshedEvent> {

	private final ObjectProvider<AbstractRefreshableRouteDefinitionLocator> locators;

	/**
	 * Creates the refresher.
	 * @param locators the provider of refreshable locators to reload
	 */
	public RefreshableRouteDefinitionLocatorRefresher(
			ObjectProvider<AbstractRefreshableRouteDefinitionLocator> locators) {
		this.locators = locators;
	}

	@Override
	public void onApplicationEvent(RefreshScopeRefreshedEvent event) {
		Flux.fromStream(this.locators.orderedStream())
			.flatMap(AbstractRefreshableRouteDefinitionLocator::refresh)
			.subscribe();
	}

}
