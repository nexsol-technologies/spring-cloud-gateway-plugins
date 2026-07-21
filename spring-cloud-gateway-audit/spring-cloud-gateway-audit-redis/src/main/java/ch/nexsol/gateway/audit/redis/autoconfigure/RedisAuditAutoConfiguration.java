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

package ch.nexsol.gateway.audit.redis.autoconfigure;

import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import ch.nexsol.gateway.audit.autoconfigure.AuditAutoConfiguration;
import ch.nexsol.gateway.audit.redis.RedisAuditEventPublisher;
import ch.nexsol.gateway.audit.redis.RedisAuditProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Registers the Redis {@link AuditEventPublisher} when {@code audit.provider=redis} and a
 * {@link ReactiveStringRedisTemplate} is available. Ordered after
 * {@link DataRedisReactiveAutoConfiguration} so the auto-configured template is already
 * defined, and before the core auto-configuration so its publisher wins over the default
 * one.
 */
@AutoConfiguration(after = DataRedisReactiveAutoConfiguration.class, before = AuditAutoConfiguration.class)
@ConditionalOnClass(ReactiveStringRedisTemplate.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.audit.provider", havingValue = "redis")
public class RedisAuditAutoConfiguration {

	/**
	 * Binds the Redis audit properties.
	 * @return the Redis audit properties bean
	 */
	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.audit.redis")
	RedisAuditProperties redisAuditProperties() {
		return new RedisAuditProperties();
	}

	/**
	 * Registers the Redis audit publisher.
	 * @param redisTemplate the reactive Redis template
	 * @param properties the Redis audit properties
	 * @param objectMapper the object mapper used to render the payload
	 * @return the Redis audit publisher bean
	 */
	@Bean
	@ConditionalOnMissingBean(AuditEventPublisher.class)
	@ConditionalOnSingleCandidate(ReactiveStringRedisTemplate.class)
	AuditEventPublisher redisAuditEventPublisher(ReactiveStringRedisTemplate redisTemplate,
			RedisAuditProperties properties, ObjectMapper objectMapper) {
		return new RedisAuditEventPublisher(redisTemplate, properties.getChannel(),
				new AuditEventSerializer(objectMapper));
	}

}
