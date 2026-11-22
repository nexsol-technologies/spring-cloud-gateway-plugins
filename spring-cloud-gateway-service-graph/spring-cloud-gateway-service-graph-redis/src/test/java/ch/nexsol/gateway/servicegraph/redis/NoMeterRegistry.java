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

package ch.nexsol.gateway.servicegraph.redis;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A provider over an empty meter registry: an instance that counted nothing yet, which
 * still publishes.
 */
final class NoMeterRegistry implements ObjectProvider<MeterRegistry> {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	@Override
	public MeterRegistry getObject() throws BeansException {
		return this.registry;
	}

	@Override
	public MeterRegistry getIfAvailable() throws BeansException {
		return this.registry;
	}

}
