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

/**
 * Configuration properties for the Redis audit provider, bound under
 * {@code spring.cloud.gateway.server.webflux.audit.redis}.
 */
public class RedisAuditProperties {

	/**
	 * Pub/sub channel the audit events are published to.
	 */
	private String channel = "gateway-audit";

	/**
	 * How many audit events may be pending at once. Past it an event is dropped, counted
	 * and reported in the log, rather than held until the heap is gone. Zero or less sets
	 * no limit.
	 */
	private int maxPending = 10_000;

	/**
	 * How long publishing one event may take before it is given up and counted as failed.
	 * Zero or less sets no timeout.
	 */
	private Duration sendTimeout = Duration.ofSeconds(30);

	/**
	 * @return the channel
	 */
	public String getChannel() {
		return this.channel;
	}

	/**
	 * @param channel the channel
	 */
	public void setChannel(String channel) {
		this.channel = channel;
	}

	/**
	 * @return how many audit events may be pending at once
	 */
	public int getMaxPending() {
		return this.maxPending;
	}

	/**
	 * @param maxPending how many audit events may be pending at once
	 */
	public void setMaxPending(int maxPending) {
		this.maxPending = maxPending;
	}

	/**
	 * @return how long a send may take
	 */
	public Duration getSendTimeout() {
		return this.sendTimeout;
	}

	/**
	 * @param sendTimeout how long a send may take
	 */
	public void setSendTimeout(Duration sendTimeout) {
		this.sendTimeout = sendTimeout;
	}

}
