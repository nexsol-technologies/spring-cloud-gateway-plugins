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

package ch.nexsol.gateway.audit.redis;

import java.time.Duration;

import ch.nexsol.gateway.audit.AuditBacklog;
import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditEventSerializer;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * {@link AuditEventPublisher} publishing the audit event, rendered as JSON, to a Redis
 * pub/sub channel. Publication is asynchronous and failures are logged without disrupting
 * the request. The events pending for a Redis that stopped answering are bounded &mdash;
 * see {@link AuditBacklog}.
 */
public class RedisAuditEventPublisher implements AuditEventPublisher {

	private final ReactiveStringRedisTemplate redisTemplate;

	private final String channel;

	private final AuditEventSerializer serializer;

	private final AuditBacklog backlog;

	private final Duration sendTimeout;

	/**
	 * Create a new publisher, bounded by the defaults of {@link RedisAuditProperties}.
	 * @param redisTemplate the reactive Redis template
	 * @param channel the destination pub/sub channel
	 * @param serializer the audit event serializer
	 */
	public RedisAuditEventPublisher(ReactiveStringRedisTemplate redisTemplate, String channel,
			AuditEventSerializer serializer) {
		this(redisTemplate, serializer, defaults(channel));
	}

	/**
	 * Create a new publisher.
	 * @param redisTemplate the reactive Redis template
	 * @param serializer the audit event serializer
	 * @param properties the channel, and the bounds on the events pending
	 */
	public RedisAuditEventPublisher(ReactiveStringRedisTemplate redisTemplate, AuditEventSerializer serializer,
			RedisAuditProperties properties) {
		this.redisTemplate = redisTemplate;
		this.channel = properties.getChannel();
		this.serializer = serializer;
		this.backlog = new AuditBacklog("the redis channel " + this.channel, properties.getMaxPending());
		this.sendTimeout = properties.getSendTimeout();
	}

	private static RedisAuditProperties defaults(String channel) {
		RedisAuditProperties properties = new RedisAuditProperties();
		properties.setChannel(channel);
		return properties;
	}

	@Override
	public void publish(AuditEvent event) {
		this.backlog.submit(() -> this.redisTemplate.convertAndSend(this.channel, this.serializer.toJson(event)),
				this.sendTimeout);
	}

	/**
	 * @return the events pending, dropped and failed
	 */
	public AuditBacklog backlog() {
		return this.backlog;
	}

}
