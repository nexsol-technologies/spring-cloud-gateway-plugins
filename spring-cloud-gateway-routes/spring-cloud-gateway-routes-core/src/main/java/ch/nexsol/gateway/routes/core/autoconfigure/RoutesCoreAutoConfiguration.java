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

import ch.nexsol.gateway.routes.core.AbstractRefreshableRouteDefinitionLocator;
import ch.nexsol.gateway.routes.core.RefreshableRouteDefinitionLocatorRefresher;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.context.scope.refresh.RefreshScopeRefreshedEvent;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration shared by all route source modules. Registers the refresher that
 * reloads every {@link AbstractRefreshableRouteDefinitionLocator} on
 * {@code /actuator/refresh} and {@code /actuator/busrefresh}. Wired only when the Spring
 * Cloud Config client refresh support is on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(RefreshScopeRefreshedEvent.class)
public class RoutesCoreAutoConfiguration {

	/**
	 * Registers the refresher reloading refreshable locators on a
	 * {@link RefreshScopeRefreshedEvent}.
	 * @param locators the provider of refreshable locators to reload
	 * @return the refresher bean
	 */
	@Bean
	@ConditionalOnMissingBean
	RefreshableRouteDefinitionLocatorRefresher refreshableRouteDefinitionLocatorRefresher(
			ObjectProvider<AbstractRefreshableRouteDefinitionLocator> locators) {
		return new RefreshableRouteDefinitionLocatorRefresher(locators);
	}

}
