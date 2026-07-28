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

package ch.nexsol.gateway.metrics.redis;

import java.time.Duration;

/**
 * Configuration properties for the Redis metrics source, bound under
 * {@code spring.cloud.gateway.server.webflux.metrics.redis}.
 */
public class RedisMetricsProperties {

	/**
	 * Prefix of the key each instance publishes its figures under. The instance id is
	 * appended, so one key exists per live instance.
	 */
	private String keyPrefix = "gateway:metrics:";

	/**
	 * How often each instance publishes its figures.
	 */
	private Duration publishInterval = Duration.ofSeconds(10);

	/**
	 * How long a published key survives. It must outlive the publish interval, otherwise
	 * an instance disappears from the figures between two of its own writes; it is what
	 * makes an instance that stopped fade out on its own.
	 */
	private Duration timeToLive = Duration.ofSeconds(45);

	/**
	 * @return the key prefix
	 */
	public String getKeyPrefix() {
		return this.keyPrefix;
	}

	/**
	 * @param keyPrefix the key prefix
	 */
	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	/**
	 * @return the publish interval
	 */
	public Duration getPublishInterval() {
		return this.publishInterval;
	}

	/**
	 * @param publishInterval the publish interval
	 */
	public void setPublishInterval(Duration publishInterval) {
		this.publishInterval = publishInterval;
	}

	/**
	 * @return the key time to live
	 */
	public Duration getTimeToLive() {
		return this.timeToLive;
	}

	/**
	 * @param timeToLive the key time to live
	 */
	public void setTimeToLive(Duration timeToLive) {
		this.timeToLive = timeToLive;
	}

}
