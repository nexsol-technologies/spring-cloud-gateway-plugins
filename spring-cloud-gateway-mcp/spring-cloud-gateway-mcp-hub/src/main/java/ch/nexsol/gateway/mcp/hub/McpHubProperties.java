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

package ch.nexsol.gateway.mcp.hub;

import java.time.Duration;

import org.springframework.util.unit.DataSize;

/**
 * Settings of the hub bridge, under {@code spring.cloud.gateway.server.webflux.mcp.hub}.
 */
public class McpHubProperties {

	private boolean enabled;

	private boolean writeEnabled;

	private Duration timeout = Duration.ofSeconds(10);

	private DataSize maxResponseSize = DataSize.ofKilobytes(256);

	private int concurrency = 4;

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isWriteEnabled() {
		return this.writeEnabled;
	}

	public void setWriteEnabled(boolean writeEnabled) {
		this.writeEnabled = writeEnabled;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public DataSize getMaxResponseSize() {
		return this.maxResponseSize;
	}

	public void setMaxResponseSize(DataSize maxResponseSize) {
		this.maxResponseSize = maxResponseSize;
	}

	public int getConcurrency() {
		return this.concurrency;
	}

	public void setConcurrency(int concurrency) {
		this.concurrency = concurrency;
	}

}
