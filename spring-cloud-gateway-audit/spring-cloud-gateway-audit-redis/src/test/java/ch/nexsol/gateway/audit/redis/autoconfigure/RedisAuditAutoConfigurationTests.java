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
import ch.nexsol.gateway.audit.DefaultAuditEventPublisher;
import ch.nexsol.gateway.audit.autoconfigure.AuditAutoConfiguration;
import ch.nexsol.gateway.audit.redis.RedisAuditEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisAuditAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class, RedisAuditAutoConfiguration.class))
		.withBean(ObjectMapper.class)
		.withBean(ReactiveStringRedisTemplate.class, () -> mock(ReactiveStringRedisTemplate.class));

	@Test
	void registersRedisPublisherWhenSelected() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.audit.provider=redis")
			.run((context) -> assertThat(context).getBean(AuditEventPublisher.class)
				.isInstanceOf(RedisAuditEventPublisher.class));
	}

	@Test
	void usesDefaultPublisherWhenNotSelected() {
		this.runner.run((context) -> assertThat(context).getBean(AuditEventPublisher.class)
			.isInstanceOf(DefaultAuditEventPublisher.class));
	}

}
