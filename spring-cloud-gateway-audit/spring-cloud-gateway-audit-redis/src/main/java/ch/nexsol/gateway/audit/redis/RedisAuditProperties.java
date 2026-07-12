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

}
