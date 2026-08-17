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

package ch.nexsol.gateway.servicegraph.autoconfigure;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.servicegraph.CallerResolver;
import ch.nexsol.gateway.servicegraph.LocalServiceGraphSource;
import ch.nexsol.gateway.servicegraph.ServiceGraphFilter;
import ch.nexsol.gateway.servicegraph.ServiceGraphProperties;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration registering the service graph plugin: the shared configuration, the
 * identity of the running instance, the filter counting the calls and, unless a provider
 * module contributed one first, the source reading the local counters.
 * <p>
 * A provider module declares its own {@link ServiceGraphSource} ordered {@code before}
 * this configuration, so its source wins over the local one.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.service-graph.enabled", matchIfMissing = true)
public class ServiceGraphAutoConfiguration {

	/**
	 * Binds the service graph properties.
	 * @return the service graph properties bean
	 */
	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.service-graph")
	public ServiceGraphProperties gatewayServiceGraphProperties() {
		return new ServiceGraphProperties();
	}

	/**
	 * Resolves the identity of the running instance once.
	 * @param properties the service graph properties holding the configured id
	 * @return the instance identity bean
	 */
	@Bean
	@ConditionalOnMissingBean
	public InstanceIdentity gatewayServiceGraphInstanceIdentity(ServiceGraphProperties properties) {
		return new InstanceIdentity(properties.getInstanceId());
	}

	/**
	 * Registers the resolver naming the caller of a request.
	 * @param properties the service graph properties holding the caller configuration
	 * @return the caller resolver bean
	 */
	@Bean
	@ConditionalOnMissingBean
	public CallerResolver serviceGraphCallerResolver(ServiceGraphProperties properties) {
		return new CallerResolver(properties.getCaller());
	}

	/**
	 * Registers the filter counting one call per routed exchange.
	 * @param meterRegistry the provider over the application meter registry
	 * @param callerResolver the resolver naming the caller
	 * @return the service graph filter bean
	 */
	@Bean
	@ConditionalOnMissingBean
	public ServiceGraphFilter serviceGraphFilter(ObjectProvider<MeterRegistry> meterRegistry,
			CallerResolver callerResolver) {
		return new ServiceGraphFilter(meterRegistry, callerResolver);
	}

	/**
	 * Registers the local source unless a provider module already contributed one.
	 * @param meterRegistry the provider over the application meter registry
	 * @param identity the identity of the running instance
	 * @return the local service graph source
	 */
	@Bean
	@ConditionalOnMissingBean(ServiceGraphSource.class)
	public LocalServiceGraphSource localServiceGraphSource(ObjectProvider<MeterRegistry> meterRegistry,
			InstanceIdentity identity) {
		return new LocalServiceGraphSource(meterRegistry, identity);
	}

}
