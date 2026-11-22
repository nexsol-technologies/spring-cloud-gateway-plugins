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

package ch.nexsol.gateway.audit.kafka.autoconfigure;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.autoconfigure.AuditAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests that the publisher a gateway gets never sends on the thread that publishes.
 * {@code KafkaProducer.send} waits for the metadata of the topic and for room in its
 * buffer, up to {@code max.block.ms} &mdash; a minute by default: called from an event
 * loop with the broker out of reach, each request parks a thread the whole gateway is
 * served by.
 */
class KafkaAuditOffloadTests {

	private final CountDownLatch brokerBack = new CountDownLatch(1);

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
				KafkaAuditAutoConfiguration.class, AuditAutoConfiguration.class))
		.withBean(ObjectMapper.class)
		.withBean(KafkaTemplate.class, () -> this.template)
		.withPropertyValues("spring.cloud.gateway.server.webflux.audit.provider=kafka");

	@AfterEach
	void letTheBlockedSendsGo() {
		this.brokerBack.countDown();
	}

	@Test
	void publishingReturnsWhileTheProducerIsBlocked() {
		given(this.template.send(anyString(), any())).willAnswer((invocation) -> {
			this.brokerBack.await(30, TimeUnit.SECONDS);
			return CompletableFuture.completedFuture(null);
		});

		this.runner.run((context) -> {
			AuditEventPublisher publisher = context.getBean(AuditEventPublisher.class);

			returnsPromptly(() -> publisher.publish(event()));

			verify(this.template, timeout(2000)).send(anyString(), any());
		});
	}

	@Test
	void theEventsHeldForABlockedProducerAreBounded() {
		given(this.template.send(anyString(), any())).willAnswer((invocation) -> {
			this.brokerBack.await(30, TimeUnit.SECONDS);
			return CompletableFuture.completedFuture(null);
		});

		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.audit.kafka.max-pending=2")
			.run((context) -> {
				AuditEventPublisher publisher = context.getBean(AuditEventPublisher.class);

				returnsPromptly(() -> {
					for (int i = 0; i < 5; i++) {
						publisher.publish(event());
					}
				});
				this.brokerBack.countDown();

				// The one being sent and the one waiting behind it; the three others were
				// dropped rather than queued.
				verify(this.template, timeout(2000).times(2)).send(anyString(), any());
				Thread.sleep(200);
				verify(this.template, times(2)).send(anyString(), any());
			});
	}

	@Test
	void everyEventIsSentWhileTheBrokerAnswers() {
		given(this.template.send(anyString(), any())).willReturn(CompletableFuture.completedFuture(null));

		this.runner.run((context) -> {
			AuditEventPublisher publisher = context.getBean(AuditEventPublisher.class);

			for (int i = 0; i < 50; i++) {
				publisher.publish(event());
			}

			verify(this.template, timeout(2000).times(50)).send(anyString(), any());
		});
	}

	@Test
	void theSendingThreadIsStoppedWithTheContext() {
		given(this.template.send(anyString(), any())).willReturn(CompletableFuture.completedFuture(null));

		this.runner.run((context) -> {
			context.getBean(AuditEventPublisher.class).publish(event());
			verify(this.template, timeout(2000)).send(anyString(), any());
			assertThat(auditThreads()).isPositive();
		});

		assertThat(auditThreads()).isZero();
	}

	/**
	 * Runs what an event loop would run, on a thread of its own, and fails when it has
	 * not returned within two seconds.
	 */
	private static void returnsPromptly(Runnable publishing) {
		CompletableFuture<Void> done = CompletableFuture.runAsync(publishing);
		assertThatCode(() -> done.get(2, TimeUnit.SECONDS)).doesNotThrowAnyException();
	}

	private static long auditThreads() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter((thread) -> thread.isAlive() && thread.getName().startsWith("audit-kafka"))
			.count();
	}

	private static AuditEvent event() {
		return new AuditEvent(Instant.now(), Map.of("request.path", "/book"));
	}

}
