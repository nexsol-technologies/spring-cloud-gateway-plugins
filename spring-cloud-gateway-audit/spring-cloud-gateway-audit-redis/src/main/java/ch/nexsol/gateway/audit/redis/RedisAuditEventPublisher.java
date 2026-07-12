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

import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * {@link AuditEventPublisher} publishing the audit event, rendered as JSON, to a Redis
 * pub/sub channel. Publication is asynchronous and failures are logged without disrupting
 * the request.
 */
public class RedisAuditEventPublisher implements AuditEventPublisher {

	private static final Logger LOG = LoggerFactory.getLogger(RedisAuditEventPublisher.class);

	private final ReactiveStringRedisTemplate redisTemplate;

	private final String channel;

	private final AuditEventSerializer serializer;

	/**
	 * Create a new publisher.
	 * @param redisTemplate the reactive Redis template
	 * @param channel the destination pub/sub channel
	 * @param serializer the audit event serializer
	 */
	public RedisAuditEventPublisher(ReactiveStringRedisTemplate redisTemplate, String channel,
			AuditEventSerializer serializer) {
		this.redisTemplate = redisTemplate;
		this.channel = channel;
		this.serializer = serializer;
	}

	@Override
	public void publish(AuditEvent event) {
		String payload = this.serializer.toJson(event);
		this.redisTemplate.convertAndSend(this.channel, payload)
			.subscribe(null, (ex) -> LOG.warn("failed to publish audit event to redis channel {}", this.channel, ex));
	}

}
