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

package ch.nexsol.gateway.validation;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what the registry does of a contract it could not read. Every request through
 * the route asks for it: read again each time, an unreachable contract costs each of them
 * a full attempt &mdash; up to the timeout of the read &mdash; before it is forwarded.
 */
class OpenapiContractRegistryFailureTests {

	private static final String MISSING = "classpath:openapi/does-not-exist.yaml";

	private final CountingLoader loader = new CountingLoader();

	@Test
	void doesNotReadAgainAtOnceAContractThatCouldNotBeRead() {
		OpenapiContractRegistry registry = new OpenapiContractRegistry(this.loader);

		StepVerifier.create(registry.contract(MISSING)).verifyError(OpenapiContractException.class);
		StepVerifier.create(registry.contract(MISSING)).verifyError(OpenapiContractException.class);
		StepVerifier.create(registry.contract(MISSING)).verifyError(OpenapiContractException.class);

		assertThat(this.loader.reads).hasValue(1);
	}

	@Test
	void readsOnceAContractThatCouldBeRead() {
		OpenapiContractRegistry registry = new OpenapiContractRegistry(this.loader);

		OpenapiContract first = registry.contract(Contracts.BOOKSTORE).block();
		OpenapiContract second = registry.contract(Contracts.BOOKSTORE).block();

		assertThat(second).isSameAs(first);
		assertThat(this.loader.reads).hasValue(1);
	}

	@Test
	void aContractThatCouldNotBeReadDoesNotKeepAnotherOneFromBeingRead() {
		OpenapiContractRegistry registry = new OpenapiContractRegistry(this.loader);

		StepVerifier.create(registry.contract(MISSING)).verifyError(OpenapiContractException.class);

		assertThat(registry.contract(Contracts.BOOKSTORE).block()).isNotNull();
	}

	@Test
	void readsAgainAContractWhoseFailureIsNoLongerRemembered() {
		OpenapiContractRegistry registry = new OpenapiContractRegistry(this.loader, Duration.ZERO);

		StepVerifier.create(registry.contract(MISSING)).verifyError(OpenapiContractException.class);
		StepVerifier.create(registry.contract(MISSING)).verifyError(OpenapiContractException.class);

		assertThat(this.loader.reads).hasValue(2);
	}

	static class CountingLoader implements ResourceLoader {

		final AtomicInteger reads = new AtomicInteger();

		private final ResourceLoader delegate = new DefaultResourceLoader();

		@Override
		public Resource getResource(String location) {
			this.reads.incrementAndGet();
			return this.delegate.getResource(location);
		}

		@Override
		public ClassLoader getClassLoader() {
			return this.delegate.getClassLoader();
		}

	}

}
