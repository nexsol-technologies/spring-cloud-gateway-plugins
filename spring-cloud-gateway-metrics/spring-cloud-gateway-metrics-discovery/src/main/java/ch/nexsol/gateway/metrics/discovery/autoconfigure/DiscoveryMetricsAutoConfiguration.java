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

package ch.nexsol.gateway.metrics.discovery.autoconfigure;

import ch.nexsol.gateway.commons.security.SecuredPaths;
import ch.nexsol.gateway.metrics.InstanceIdentity;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.metrics.LocalInstanceMetricsSource;
import ch.nexsol.gateway.metrics.LocalRouteMetricsSource;
import ch.nexsol.gateway.metrics.MetricsProperties;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration;
import ch.nexsol.gateway.metrics.discovery.DiscoveryInstanceMetricsSource;
import ch.nexsol.gateway.metrics.discovery.DiscoveryMetricsProperties;
import ch.nexsol.gateway.metrics.discovery.DiscoveryRouteMetricsSource;
import ch.nexsol.gateway.metrics.discovery.LocalInstanceMetricsController;
import ch.nexsol.gateway.metrics.discovery.LocalRouteMetricsController;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.config.HttpClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Registers the service discovery {@link RouteMetricsSource} when
 * {@code metrics.provider=discovery}. Ordered before the core auto-configuration so its
 * source wins over the local meter registry.
 */
@AutoConfiguration(before = MetricsAutoConfiguration.class)
@ConditionalOnClass({ ReactiveDiscoveryClient.class, WebClient.class, MeterRegistry.class })
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.enabled", matchIfMissing = true)
public class DiscoveryMetricsAutoConfiguration {

	/**
	 * Holds the beans behind the provider selector. Two levels because
	 * {@code @ConditionalOnProperty} is not repeatable.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.provider", havingValue = "discovery")
	@Import(LocalRouteMetricsController.class)
	static class DiscoverySourceConfiguration {

		/**
		 * Serves the technical figures of this instance to its siblings. Registered under
		 * the same condition as the source it reads: without it there is nothing to
		 * serve.
		 * @param localSource the source reading this instance's meter registry
		 * @return the local instance metrics endpoint
		 */
		@Bean
		@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.instance.enabled",
				matchIfMissing = true)
		LocalInstanceMetricsController localInstanceMetricsController(LocalInstanceMetricsSource localSource) {
			return new LocalInstanceMetricsController(localSource);
		}

		/**
		 * Binds the discovery metrics properties.
		 * @return the discovery metrics properties bean
		 */
		@Bean
		@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.metrics.discovery")
		DiscoveryMetricsProperties discoveryMetricsProperties() {
			return new DiscoveryMetricsProperties();
		}

		/**
		 * Declares the endpoints this instance serves to its siblings, so that whoever
		 * governs the paths of the gateway knows they exist rather than leaving them to
		 * whatever catch-all rule the application happens to have.
		 * <p>
		 * They are declared open: the fan-out polls them with a client that carries no
		 * credentials, so closing them would not protect the figures, it would leave
		 * every instance reporting its own traffic alone. The paths are the configured
		 * ones, not the constants: moving one moves what the siblings poll, and what is
		 * declared has to move with it.
		 * @param properties the discovery configuration
		 * @return the paths this instance serves to its siblings
		 */
		@Bean
		@ConditionalOnMissingBean(name = "discoveryMetricsSecuredPaths")
		SecuredPaths discoveryMetricsSecuredPaths(DiscoveryMetricsProperties properties) {
			return SecuredPaths.open(properties.getPath(), properties.getInstancePath());
		}

		/**
		 * Registers the local source explicitly. The core would have declared it, but
		 * only as long as no other source exists &mdash; and this module declares one.
		 * The endpoint the siblings poll is bound to this bean, so it keeps answering
		 * with this instance's own figures.
		 * @param meterRegistry the provider over the application meter registry
		 * @param properties the shared metrics configuration
		 * @param identity the identity of the running instance
		 * @return the local route metrics source
		 */
		@Bean
		LocalRouteMetricsSource localRouteMetricsSource(ObjectProvider<MeterRegistry> meterRegistry,
				MetricsProperties properties, InstanceIdentity identity) {
			return new LocalRouteMetricsSource(meterRegistry, properties, identity);
		}

		/**
		 * Registers the source consolidating every registered instance. Marked primary
		 * because the local source is a {@link RouteMetricsSource} too: the views must
		 * get the consolidated figures, the endpoint keeps the local ones.
		 * @param discoveryClient the provider over the registry the siblings are listed
		 * from
		 * @param builder the application web client builder
		 * @param properties the discovery configuration
		 * @param environment the environment the default service id is read from
		 * @return the discovery route metrics source
		 */
		@Bean
		@Primary
		@ConditionalOnMissingBean(DiscoveryRouteMetricsSource.class)
		DiscoveryRouteMetricsSource discoveryRouteMetricsSource(ObjectProvider<ReactiveDiscoveryClient> discoveryClient,
				WebClient.Builder builder, DiscoveryMetricsProperties properties, Environment environment) {
			String serviceId = StringUtils.hasText(properties.getServiceId()) ? properties.getServiceId()
					: environment.getProperty("spring.application.name", "");
			return new DiscoveryRouteMetricsSource(discoveryClient, builder.build(), properties, serviceId);
		}

		/**
		 * Registers the local instance source explicitly, for the same reason the local
		 * route source is: the endpoint the siblings poll is bound to this bean and has
		 * to keep answering with this instance's own figures.
		 * @param meterRegistry the provider over the application meter registry
		 * @param httpClientProperties the provider over the gateway HTTP client
		 * configuration
		 * @param properties the shared metrics configuration
		 * @param identity the identity of the running instance
		 * @return the local instance metrics source
		 */
		@Bean
		@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.instance.enabled",
				matchIfMissing = true)
		LocalInstanceMetricsSource localInstanceMetricsSource(ObjectProvider<MeterRegistry> meterRegistry,
				ObjectProvider<HttpClientProperties> httpClientProperties, MetricsProperties properties,
				InstanceIdentity identity) {
			return new LocalInstanceMetricsSource(meterRegistry, httpClientProperties, properties, identity);
		}

		/**
		 * Registers the source listing every registered instance. Marked primary because
		 * the local source is an {@link InstanceMetricsSource} too: the views must get
		 * every instance, the endpoint keeps answering with this one.
		 * @param discoveryClient the provider over the registry the siblings are listed
		 * from
		 * @param builder the application web client builder
		 * @param properties the discovery configuration
		 * @param environment the environment the default service id is read from
		 * @return the discovery instance metrics source
		 */
		@Bean
		@Primary
		@ConditionalOnMissingBean(DiscoveryInstanceMetricsSource.class)
		DiscoveryInstanceMetricsSource discoveryInstanceMetricsSource(
				ObjectProvider<ReactiveDiscoveryClient> discoveryClient, WebClient.Builder builder,
				DiscoveryMetricsProperties properties, Environment environment) {
			String serviceId = StringUtils.hasText(properties.getServiceId()) ? properties.getServiceId()
					: environment.getProperty("spring.application.name", "");
			return new DiscoveryInstanceMetricsSource(discoveryClient, builder.build(), properties, serviceId);
		}

	}

}
