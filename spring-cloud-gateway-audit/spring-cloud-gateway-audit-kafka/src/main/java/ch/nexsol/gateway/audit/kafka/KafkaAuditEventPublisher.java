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

import java.util.concurrent.RejectedExecutionException;

import ch.nexsol.gateway.audit.AuditBacklog;
import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@link AuditEventPublisher} sending the audit event, rendered as JSON, to a Kafka
 * topic. The send is asynchronous and failures are logged without disrupting the request.
 * <p>
 * Asynchronous is not non-blocking here: {@code KafkaProducer.send} waits on the calling
 * thread for the metadata of the topic and for room in its buffer, up to
 * {@code max.block.ms} &mdash; a minute by default. Called from an event loop with the
 * broker out of reach, it parks the thread every other request is served by. The
 * publisher a gateway gets therefore hands the events to a thread of its own, and bounds
 * the ones waiting for it &mdash; see {@link AuditBacklog}.
 */
public class KafkaAuditEventPublisher implements AuditEventPublisher, DisposableBean {

	private final KafkaTemplate<Object, Object> kafkaTemplate;

	private final String topic;

	private final AuditEventSerializer serializer;

	private final AuditBacklog backlog;

	private final Scheduler scheduler;

	/**
	 * Create a publisher sending on the thread that publishes, which may block it for up
	 * to {@code max.block.ms}: for a caller that is not on an event loop.
	 * @param kafkaTemplate the template used to send the event
	 * @param topic the destination topic
	 * @param serializer the audit event serializer
	 */
	public KafkaAuditEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate, String topic,
			AuditEventSerializer serializer) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
		this.serializer = serializer;
		this.backlog = new AuditBacklog("the kafka topic " + topic, 0);
		this.scheduler = null;
	}

	/**
	 * Create a publisher sending on a thread of its own, so the thread that publishes is
	 * never blocked.
	 * @param kafkaTemplate the template used to send the event
	 * @param serializer the audit event serializer
	 * @param properties the destination topic, and the bound on the events waiting to be
	 * handed to the producer
	 */
	public KafkaAuditEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate, AuditEventSerializer serializer,
			KafkaAuditProperties properties) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = properties.getTopic();
		this.serializer = serializer;
		this.backlog = new AuditBacklog("the kafka topic " + this.topic, properties.getMaxPending());
		// One thread: the producer is what sends in parallel, this thread only hands it
		// the records, and a single one keeps them in the order they were published.
		this.scheduler = Schedulers.newSingle("audit-kafka", true);
	}

	@Override
	public void publish(AuditEvent event) {
		if (this.scheduler == null) {
			send(event);
			return;
		}
		if (!this.backlog.tryAcquire()) {
			return;
		}
		try {
			this.scheduler.schedule(() -> {
				try {
					send(event);
				}
				catch (RuntimeException ex) {
					this.backlog.failed(ex);
				}
				finally {
					this.backlog.release();
				}
			});
		}
		catch (RejectedExecutionException ex) {
			// The gateway is shutting down and the thread is gone.
			this.backlog.release();
			this.backlog.failed(ex);
		}
	}

	private void send(AuditEvent event) {
		String payload = this.serializer.toJson(event);
		this.kafkaTemplate.send(this.topic, payload).whenComplete((result, ex) -> {
			if (ex != null) {
				this.backlog.failed(ex);
			}
		});
	}

	/**
	 * @return the events pending, dropped and failed
	 */
	public AuditBacklog backlog() {
		return this.backlog;
	}

	@Override
	public void destroy() {
		if (this.scheduler != null) {
			this.scheduler.dispose();
		}
	}

}
