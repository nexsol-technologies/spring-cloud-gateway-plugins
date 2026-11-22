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

package ch.nexsol.gateway.openapi.hub.filter;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.factory.rewrite.RewriteFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies what rewriting a proxied OpenAPI document costs the gateway. The document
 * endpoints are open to anybody, a document weighs up to megabytes, and rewriting it is a
 * full parse and a full rendering: done on the thread routing the traffic for every
 * request, reading the documentation is enough to slow the gateway down.
 */
class OpenapiDocumentRewriteCostTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final RecordingFactory factory = new RecordingFactory();

	@Test
	void rewritesADocumentAwayFromTheThreadItArrivedOn() {
		RewriteFunction<byte[], byte[]> rewrite = this.factory.rewriteServersWithGatewayUrl("/service-a");

		byte[] result = Mono.from(rewrite.apply(null, document("1.0.0"))).block(TIMEOUT);

		assertThat(new String(result, StandardCharsets.UTF_8)).contains("https://gateway.example.ch/service-a");
		assertThat(this.factory.rewrittenOn.get()).startsWith("boundedElastic");
	}

	@Test
	void doesNotRewriteAgainTheDocumentItJustRewrote() {
		RewriteFunction<byte[], byte[]> rewrite = this.factory.rewriteServersWithGatewayUrl("/service-a");

		byte[] first = Mono.from(rewrite.apply(null, document("1.0.0"))).block(TIMEOUT);
		byte[] second = Mono.from(rewrite.apply(null, document("1.0.0"))).block(TIMEOUT);

		assertThat(second).isEqualTo(first);
		assertThat(this.factory.rewrites).hasValue(1);
	}

	@Test
	void rewritesAgainADocumentThatChanged() {
		RewriteFunction<byte[], byte[]> rewrite = this.factory.rewriteServersWithGatewayUrl("/service-a");

		Mono.from(rewrite.apply(null, document("1.0.0"))).block(TIMEOUT);
		byte[] changed = Mono.from(rewrite.apply(null, document("2.0.0"))).block(TIMEOUT);

		assertThat(new String(changed, StandardCharsets.UTF_8)).contains("2.0.0").doesNotContain("1.0.0");
		assertThat(this.factory.rewrites).hasValue(2);
	}

	@Test
	void keepsTheDocumentOfEachRouteApart() {
		byte[] forA = Mono.from(this.factory.rewriteServersWithGatewayUrl("/service-a").apply(null, document("1.0.0")))
			.block(TIMEOUT);
		byte[] forB = Mono.from(this.factory.rewriteServersWithGatewayUrl("/service-b").apply(null, document("1.0.0")))
			.block(TIMEOUT);

		assertThat(new String(forA, StandardCharsets.UTF_8)).contains("https://gateway.example.ch/service-a");
		assertThat(new String(forB, StandardCharsets.UTF_8)).contains("https://gateway.example.ch/service-b");
	}

	@Test
	void stillAnswersNothingForNoDocument() {
		RewriteFunction<byte[], byte[]> rewrite = this.factory.rewriteServersWithGatewayUrl("/service-a");

		assertThat(Mono.from(rewrite.apply(null, null)).block(TIMEOUT)).isNull();
		assertThat(Mono.from(rewrite.apply(null, new byte[0])).block(TIMEOUT)).isEmpty();
	}

	private static byte[] document(String version) {
		return ("{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"A\",\"version\":\"" + version
				+ "\"},\"servers\":[{\"url\":\"http://service-a:8080\"}],\"paths\":{}}")
			.getBytes(StandardCharsets.UTF_8);
	}

	static class RecordingFactory extends OpenapiModifyResponseBodyGatewayFilterFactory {

		final AtomicInteger rewrites = new AtomicInteger();

		final AtomicReference<String> rewrittenOn = new AtomicReference<>();

		RecordingFactory() {
			super(List.of(), Set.of(), Set.of(), URI.create("https://gateway.example.ch"));
		}

		@Override
		byte[] rewrite(byte[] body, String path) {
			this.rewrites.incrementAndGet();
			this.rewrittenOn.set(Thread.currentThread().getName());
			return super.rewrite(body, path);
		}

	}

}
