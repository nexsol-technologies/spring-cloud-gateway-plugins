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

package ch.nexsol.gateway.audit.kafka.autoconfigure;

import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import ch.nexsol.gateway.audit.autoconfigure.AuditAutoConfiguration;
import ch.nexsol.gateway.audit.kafka.KafkaAuditEventPublisher;
import ch.nexsol.gateway.audit.kafka.KafkaAuditProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Registers the Kafka {@link AuditEventPublisher} when {@code audit.provider=kafka} and a
 * {@link KafkaTemplate} is available. Ordered after {@link KafkaAutoConfiguration} so the
 * auto-configured template is already defined, and before the core auto-configuration so
 * its publisher wins over the default one.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class, before = AuditAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.audit.provider", havingValue = "kafka")
public class KafkaAuditAutoConfiguration {

	/**
	 * Binds the Kafka audit properties.
	 * @return the Kafka audit properties bean
	 */
	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.audit.kafka")
	KafkaAuditProperties kafkaAuditProperties() {
		return new KafkaAuditProperties();
	}

	/**
	 * Registers the Kafka audit publisher.
	 * @param kafkaTemplate the Kafka template
	 * @param properties the Kafka audit properties
	 * @param objectMapper the object mapper used to render the payload
	 * @return the Kafka audit publisher bean
	 */
	@Bean
	@ConditionalOnMissingBean(AuditEventPublisher.class)
	@ConditionalOnSingleCandidate(KafkaTemplate.class)
	AuditEventPublisher kafkaAuditEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate,
			KafkaAuditProperties properties, ObjectMapper objectMapper) {
		return new KafkaAuditEventPublisher(kafkaTemplate, properties.getTopic(),
				new AuditEventSerializer(objectMapper));
	}

}
