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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.session.ReactiveSessionRepository;
import org.springframework.session.data.redis.ReactiveRedisSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.session.DefaultWebSessionManager;
import org.springframework.web.server.session.InMemoryWebSessionStore;
import org.springframework.web.server.session.WebSessionManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies which session store the sample wires.
 * <p>
 * As in {@link ServiceGraphProfileTests}, what is asserted is the wiring: no Redis is
 * contacted while a context merely starts, the repository connects on the first session
 * it is asked to read or write.
 */
class SessionsProfileTests {

	@Nested
	@SpringBootTest(properties = { "eureka.client.enabled=false",
			"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
	class DefaultProfile {

		@Autowired
		WebSessionManager webSessionManager;

		@Autowired
		ApplicationContext context;

		@Test
		void shouldKeepTheSessionsInTheMemoryOfThisInstance() {
			// SessionDataRedisAutoConfiguration activates on a
			// ReactiveRedisConnectionFactory
			// bean, which the redis sources of this sample put in the context. Excluded
			// on
			// this profile, so running the sample asks for no Redis.
			assertThat(this.context.getBeanNamesForType(ReactiveSessionRepository.class)).isEmpty();
			assertThat(this.webSessionManager).isInstanceOf(DefaultWebSessionManager.class);
			assertThat(((DefaultWebSessionManager) this.webSessionManager).getSessionStore())
				.isInstanceOf(InMemoryWebSessionStore.class);
		}

	}

	@Nested
	@SpringBootTest(properties = { "eureka.client.enabled=false",
			"spring.cloud.gateway.server.webflux.routes-openapi.enabled=false" })
	@ActiveProfiles("sessions-redis")
	class SessionsRedisProfile {

		@Autowired
		ReactiveSessionRepository<?> sessionRepository;

		@Test
		void shouldHoldTheSessionsOutsideThisInstance() {
			// The profile carries an empty spring.autoconfigure.exclude, which is what
			// lifts
			// the exclusion the default document declares.
			assertThat(this.sessionRepository).isInstanceOf(ReactiveRedisSessionRepository.class);
		}

	}

}
