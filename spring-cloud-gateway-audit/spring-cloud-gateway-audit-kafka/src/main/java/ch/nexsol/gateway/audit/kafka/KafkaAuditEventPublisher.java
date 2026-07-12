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

package ch.nexsol.gateway.audit.kafka;

import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@link AuditEventPublisher} sending the audit event, rendered as JSON, to a Kafka
 * topic. The send is asynchronous and failures are logged without disrupting the request.
 */
public class KafkaAuditEventPublisher implements AuditEventPublisher {

	private static final Logger LOG = LoggerFactory.getLogger(KafkaAuditEventPublisher.class);

	private final KafkaTemplate<Object, Object> kafkaTemplate;

	private final String topic;

	private final AuditEventSerializer serializer;

	/**
	 * Create a new publisher.
	 * @param kafkaTemplate the template used to send the event
	 * @param topic the destination topic
	 * @param serializer the audit event serializer
	 */
	public KafkaAuditEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate, String topic,
			AuditEventSerializer serializer) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
		this.serializer = serializer;
	}

	@Override
	public void publish(AuditEvent event) {
		String payload = this.serializer.toJson(event);
		this.kafkaTemplate.send(this.topic, payload).whenComplete((result, ex) -> {
			if (ex != null) {
				LOG.warn("failed to publish audit event to kafka topic {}", this.topic, ex);
			}
		});
	}

}
