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

package ch.nexsol.gateway.metrics.redis.autoconfigure;

import ch.nexsol.gateway.metrics.InstanceIdentity;
import ch.nexsol.gateway.metrics.LocalRouteMetricsSource;
import ch.nexsol.gateway.metrics.MetricsProperties;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration;
import ch.nexsol.gateway.metrics.redis.RedisMetricsProperties;
import ch.nexsol.gateway.metrics.redis.RedisRouteMetricsPublisher;
import ch.nexsol.gateway.metrics.redis.RedisRouteMetricsSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Registers the Redis {@link RouteMetricsSource} when {@code metrics.provider=redis}.
 * Ordered before the core auto-configuration so its source wins over the local meter
 * registry.
 */
@AutoConfiguration(before = MetricsAutoConfiguration.class)
@ConditionalOnClass({ ReactiveStringRedisTemplate.class, MeterRegistry.class })
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.enabled", matchIfMissing = true)
public class RedisMetricsAutoConfiguration {

	/**
	 * Holds the beans behind the provider selector. Two levels because
	 * {@code @ConditionalOnProperty} is not repeatable.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.provider", havingValue = "redis")
	static class RedisSourceConfiguration {

		/**
		 * Binds the Redis metrics properties.
		 * @return the Redis metrics properties bean
		 */
		@Bean
		@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.metrics.redis")
		RedisMetricsProperties redisMetricsProperties() {
			return new RedisMetricsProperties();
		}

		/**
		 * Registers the local source explicitly: this instance still has to read its own
		 * meter registry, to publish it. The core would only have declared it as long as
		 * no other source exists, and this module declares one.
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
		 * Registers the task publishing this instance's figures.
		 * @param redisTemplate the reactive Redis template
		 * @param localSource the local route metrics source
		 * @param properties the Redis configuration
		 * @param objectMapper the mapper rendering the figures
		 * @param identity the identity of the running instance
		 * @return the publisher
		 */
		@Bean
		@ConditionalOnSingleCandidate(ReactiveStringRedisTemplate.class)
		RedisRouteMetricsPublisher redisRouteMetricsPublisher(ReactiveStringRedisTemplate redisTemplate,
				LocalRouteMetricsSource localSource, RedisMetricsProperties properties, ObjectMapper objectMapper,
				InstanceIdentity identity) {
			return new RedisRouteMetricsPublisher(redisTemplate, localSource, properties, objectMapper, identity);
		}

		/**
		 * Registers the source reading what every instance published. Marked primary
		 * because the local source is a {@link RouteMetricsSource} too: the views must
		 * get the consolidated figures.
		 * @param redisTemplate the reactive Redis template
		 * @param properties the Redis configuration
		 * @param objectMapper the mapper reading the published figures
		 * @return the Redis route metrics source
		 */
		@Bean
		@Primary
		@ConditionalOnMissingBean(RedisRouteMetricsSource.class)
		@ConditionalOnSingleCandidate(ReactiveStringRedisTemplate.class)
		RedisRouteMetricsSource redisRouteMetricsSource(ReactiveStringRedisTemplate redisTemplate,
				RedisMetricsProperties properties, ObjectMapper objectMapper) {
			return new RedisRouteMetricsSource(redisTemplate, properties, objectMapper);
		}

	}

}
