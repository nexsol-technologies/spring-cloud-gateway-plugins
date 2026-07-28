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

package ch.nexsol.gateway.metrics.autoconfigure;

import ch.nexsol.gateway.metrics.InstanceIdentity;
import ch.nexsol.gateway.metrics.LocalRouteMetricsSource;
import ch.nexsol.gateway.metrics.MetricsProperties;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration registering the metrics plugin: the shared configuration, the
 * identity of the running instance and, unless a provider module contributed one first,
 * the source reading the local meter registry.
 * <p>
 * A provider module declares its own {@link RouteMetricsSource} ordered {@code before}
 * this configuration, so its source wins over the local one exactly the way an audit
 * provider wins over the default publisher.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.enabled", matchIfMissing = true)
public class MetricsAutoConfiguration {

	/**
	 * Binds the metrics properties.
	 * @return the metrics properties bean
	 */
	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.metrics")
	public MetricsProperties gatewayMetricsProperties() {
		return new MetricsProperties();
	}

	/**
	 * Resolves the identity of the running instance once.
	 * @param properties the metrics properties holding the configured id
	 * @return the instance identity bean
	 */
	@Bean
	@ConditionalOnMissingBean
	public InstanceIdentity gatewayInstanceIdentity(MetricsProperties properties) {
		return new InstanceIdentity(properties.getInstanceId());
	}

	/**
	 * Registers the local source unless a provider module already contributed one.
	 * @param meterRegistry the provider over the application meter registry
	 * @param properties the metrics properties
	 * @param identity the identity of the running instance
	 * @return the local route metrics source
	 */
	@Bean
	@ConditionalOnMissingBean(RouteMetricsSource.class)
	public LocalRouteMetricsSource localRouteMetricsSource(ObjectProvider<MeterRegistry> meterRegistry,
			MetricsProperties properties, InstanceIdentity identity) {
		return new LocalRouteMetricsSource(meterRegistry, properties, identity);
	}

}
