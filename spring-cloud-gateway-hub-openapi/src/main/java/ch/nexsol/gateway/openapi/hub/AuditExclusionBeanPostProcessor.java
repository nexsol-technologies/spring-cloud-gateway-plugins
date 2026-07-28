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

package ch.nexsol.gateway.openapi.hub;

import java.util.List;

import ch.nexsol.gateway.audit.AuditProperties;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Excludes the documentation endpoints of the hub from the global auditing web filter.
 * <p>
 * A console polling the contracts calls them over and over, which drowns the audit trail
 * in traffic that says nothing about what the gateway routed. The exclusions are the
 * paths the hub declares through {@link HubOpenapiPaths}, the same list its security
 * chain opens.
 * <p>
 * Note that {@code /v3/api-docs/*} covers the aggregated contracts, which are real
 * proxied routes: excluding them takes genuinely routed traffic out of the trail. Narrow
 * the list with
 * {@code spring.cloud.gateway.server.webflux.audit.web-filter.exclude-paths} if that is
 * not wanted.
 * <p>
 * The SpringDoc configuration is resolved lazily: a bean post-processor is created very
 * early, before the beans it reads exist.
 */
public class AuditExclusionBeanPostProcessor implements BeanPostProcessor {

	private final ObjectProvider<SpringDocConfigProperties> springDocProperties;

	private final ObjectProvider<SwaggerUiConfigProperties> swaggerUiProperties;

	/**
	 * Creates the post-processor.
	 * @param springDocProperties the SpringDoc configuration, when it is on
	 * @param swaggerUiProperties the Swagger UI configuration, when it is on
	 */
	public AuditExclusionBeanPostProcessor(ObjectProvider<SpringDocConfigProperties> springDocProperties,
			ObjectProvider<SwaggerUiConfigProperties> swaggerUiProperties) {
		this.springDocProperties = springDocProperties;
		this.swaggerUiProperties = swaggerUiProperties;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof AuditProperties properties)) {
			return bean;
		}
		List<String> excluded = properties.getWebFilter().getExcludePaths();
		HubOpenapiPaths.documentationPaths(this.springDocProperties, this.swaggerUiProperties)
			.stream()
			.filter((path) -> !excluded.contains(path))
			.forEach(excluded::add);
		return bean;
	}

}
