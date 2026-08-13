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

package ch.nexsol.gateway.metrics.discovery;

import ch.nexsol.gateway.metrics.InstanceMetric;
import ch.nexsol.gateway.metrics.LocalInstanceMetricsSource;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the technical figures of this instance, for the siblings to poll.
 */
@RestController
public class LocalInstanceMetricsController {

	private final LocalInstanceMetricsSource localSource;

	/**
	 * Creates the endpoint over the local source.
	 * @param localSource the source reading this instance's meter registry
	 */
	public LocalInstanceMetricsController(LocalInstanceMetricsSource localSource) {
		this.localSource = localSource;
	}

	/**
	 * Returns the figures of this instance.
	 * @return the figures of this instance, never the consolidated ones
	 */
	// Bound to the configured path rather than to the constant: the fan-out polls
	// `discovery.instance-path`, so moving it would otherwise leave every sibling polling
	// a path no instance serves, and reporting them all as unreachable.
	@GetMapping("${spring.cloud.gateway.server.webflux.metrics.discovery.instance-path:"
			+ DiscoveryMetricsProperties.DEFAULT_INSTANCE_PATH + "}")
	public InstanceMetric local() {
		return this.localSource.read();
	}

}
