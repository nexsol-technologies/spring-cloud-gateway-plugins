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

package ch.nexsol.gateway.ui.audit;

import java.util.List;

import ch.nexsol.gateway.audit.AuditProperties;
import ch.nexsol.gateway.ui.security.UiSecuredPaths;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Excludes the paths the console serves from the global auditing web filter, so that
 * browsing the shell does not fill the audit trail with the traffic of the shell itself:
 * its pages, its HTMX fragments and its static assets are not gateway traffic.
 * <p>
 * The exclusions are the exact paths every active view already declares through
 * {@link UiSecuredPaths}, which is the authoritative list of what the console serves: a
 * view that is not active excludes nothing, and a gateway route declared under
 * {@code /ui} keeps being audited.
 * <p>
 * The contributions are resolved lazily: a bean post-processor is created very early,
 * before the beans it reads exist.
 */
public class AuditExclusionBeanPostProcessor implements BeanPostProcessor {

	private final ObjectProvider<UiSecuredPaths> securedPaths;

	/**
	 * Creates the post-processor.
	 * @param securedPaths the provider over the paths contributed by the active views
	 */
	public AuditExclusionBeanPostProcessor(ObjectProvider<UiSecuredPaths> securedPaths) {
		this.securedPaths = securedPaths;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof AuditProperties properties)) {
			return bean;
		}
		List<String> excluded = properties.getWebFilter().getExcludePaths();
		this.securedPaths.orderedStream()
			.flatMap((contribution) -> contribution.paths().stream())
			.filter((path) -> !excluded.contains(path))
			.forEach(excluded::add);
		return bean;
	}

}
