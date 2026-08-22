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

package ch.nexsol.gateway.sample;

import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import ch.nexsol.gateway.servicegraph.redis.RedisServiceGraphPublisher;
import ch.nexsol.gateway.servicegraph.redis.RedisServiceGraphSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the {@code graph-redis} profile selects the consolidating graph source,
 * and that two instances publish under two keys.
 * <p>
 * As in {@link MetricsProfilesTests}, what is asserted is the wiring: no Redis is
 * contacted while a context merely starts, the source reads on demand.
 */
class ServiceGraphProfileTests {

	@Nested
	@SpringBootTest(properties = { "eureka.client.enabled=false",
			"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
	@ActiveProfiles("graph-redis")
	class RedisProfile {

		@Autowired
		ServiceGraphSource serviceGraphSource;

		@Autowired
		RedisServiceGraphPublisher publisher;

		@Test
		void shouldReadEveryInstanceRatherThanTheCountersOfThisOne() {
			assertThat(this.serviceGraphSource).isInstanceOf(RedisServiceGraphSource.class);
			assertThat(this.publisher.key()).isEqualTo("gateway:service-graph:gateway-full-1");
		}

	}

	@Nested
	@SpringBootTest(properties = { "eureka.client.enabled=false",
			"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
	@ActiveProfiles({ "graph-redis", "instance2" })
	class SecondInstance {

		@Autowired
		RedisServiceGraphPublisher publisher;

		/**
		 * The identity naming the key is the one of the metrics plugin: both declare the
		 * same InstanceIdentity bean, the metrics one is registered first, and
		 * {@code service-graph.instance-id} is read by nobody in this sample. Should that
		 * ordering ever change, the two instances would publish under the same key and
		 * the graph would show one of them.
		 */
		@Test
		void shouldPublishUnderAKeyOfItsOwn() {
			assertThat(this.publisher.key()).isEqualTo("gateway:service-graph:gateway-full-2");
		}

	}

}
