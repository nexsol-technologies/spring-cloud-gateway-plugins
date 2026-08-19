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

package ch.nexsol.gateway.servicegraph.redis.autoconfigure;

import ch.nexsol.gateway.commons.InstanceIdentity;
import ch.nexsol.gateway.servicegraph.LocalServiceGraphSource;
import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import ch.nexsol.gateway.servicegraph.autoconfigure.ServiceGraphAutoConfiguration;
import ch.nexsol.gateway.servicegraph.redis.RedisServiceGraphProperties;
import ch.nexsol.gateway.servicegraph.redis.RedisServiceGraphPublisher;
import ch.nexsol.gateway.servicegraph.redis.RedisServiceGraphSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Registers the Redis {@link ServiceGraphSource} when
 * {@code service-graph.provider=redis}. Ordered before the core auto-configuration so its
 * source wins over the local counters.
 */
@AutoConfiguration(after = DataRedisReactiveAutoConfiguration.class, before = ServiceGraphAutoConfiguration.class)
@ConditionalOnClass({ ReactiveStringRedisTemplate.class, MeterRegistry.class })
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.service-graph.enabled", matchIfMissing = true)
public class RedisServiceGraphAutoConfiguration {

	/**
	 * Holds the beans behind the provider selector. Two levels because
	 * {@code @ConditionalOnProperty} is not repeatable.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.service-graph.provider", havingValue = "redis")
	static class RedisSourceConfiguration {

		/**
		 * Binds the Redis service graph properties.
		 * @return the Redis service graph properties bean
		 */
		@Bean
		@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.service-graph.redis")
		RedisServiceGraphProperties redisServiceGraphProperties() {
			return new RedisServiceGraphProperties();
		}

		/**
		 * Registers the local source explicitly: this instance still has to read its own
		 * counters, to publish them. The core would only have declared it as long as no
		 * other source exists, and this module declares one.
		 * @param meterRegistry the provider over the application meter registry
		 * @param identity the identity of the running instance
		 * @return the local service graph source
		 */
		@Bean
		LocalServiceGraphSource localServiceGraphSource(ObjectProvider<MeterRegistry> meterRegistry,
				InstanceIdentity identity) {
			return new LocalServiceGraphSource(meterRegistry, identity);
		}

		/**
		 * Registers the task publishing this instance's edges.
		 * @param redisTemplate the reactive Redis template
		 * @param localSource the local service graph source
		 * @param properties the Redis configuration
		 * @param objectMapper the mapper rendering the edges
		 * @param identity the identity of the running instance
		 * @return the publisher
		 */
		@Bean
		@ConditionalOnSingleCandidate(ReactiveStringRedisTemplate.class)
		RedisServiceGraphPublisher redisServiceGraphPublisher(ReactiveStringRedisTemplate redisTemplate,
				LocalServiceGraphSource localSource, RedisServiceGraphProperties properties, ObjectMapper objectMapper,
				InstanceIdentity identity) {
			return new RedisServiceGraphPublisher(redisTemplate, localSource, properties, objectMapper, identity);
		}

		/**
		 * Registers the source reading what every instance published. Marked primary
		 * because the local source is a {@link ServiceGraphSource} too: the view must get
		 * the consolidated graph.
		 * @param redisTemplate the reactive Redis template
		 * @param properties the Redis configuration
		 * @param objectMapper the mapper reading the published edges
		 * @return the Redis service graph source
		 */
		@Bean
		@Primary
		@ConditionalOnMissingBean(RedisServiceGraphSource.class)
		@ConditionalOnSingleCandidate(ReactiveStringRedisTemplate.class)
		RedisServiceGraphSource redisServiceGraphSource(ReactiveStringRedisTemplate redisTemplate,
				RedisServiceGraphProperties properties, ObjectMapper objectMapper) {
			return new RedisServiceGraphSource(redisTemplate, properties, objectMapper);
		}

	}

}
