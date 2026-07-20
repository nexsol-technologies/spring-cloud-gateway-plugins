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

package ch.nexsol.gateway.routes.openapi;

import java.time.Duration;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import org.springframework.context.SmartLifecycle;

/**
 * Generates the OpenAPI routes once the context is running and, when an update interval
 * is configured, reloads every source on a fixed delay. Runs in the last startup phase so
 * the gateway route infrastructure is ready before the initial refresh event is
 * published.
 */
public class RouteOpenapiLifecycle implements SmartLifecycle {

	private final OpenApiRouteDefinitionLocator locator;

	private final Duration updateInterval;

	private volatile boolean running;

	private Disposable poll;

	/**
	 * Creates the lifecycle.
	 * @param locator the locator to refresh
	 * @param updateInterval the fixed delay between reloads, or {@code null} to load once
	 */
	public RouteOpenapiLifecycle(OpenApiRouteDefinitionLocator locator, Duration updateInterval) {
		this.locator = locator;
		this.updateInterval = updateInterval;
	}

	@Override
	public void start() {
		this.running = true;
		// Block on the initial load so routes are available before startup completes.
		this.locator.refresh().block();
		if (this.updateInterval != null && !this.updateInterval.isZero() && !this.updateInterval.isNegative()) {
			this.poll = Flux.interval(this.updateInterval, this.updateInterval)
				.concatMap((tick) -> this.locator.refresh())
				.subscribe();
		}
	}

	@Override
	public void stop() {
		this.running = false;
		if (this.poll != null) {
			this.poll.dispose();
		}
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE;
	}

}
