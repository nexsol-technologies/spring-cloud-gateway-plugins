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

package ch.nexsol.gateway.mcp.server;

import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class WriteGuardTests {

	private final AtomicInteger runs = new AtomicInteger();

	@Test
	void describesTheChangeAndDoesNothingUntilTheCallIsConfirmed() {
		WriteGuard guard = new WriteGuard(true);
		StepVerifier.create(guard.run(null, "delete the route 'books'", this::change)).assertNext((result) -> {
			assertThat(result.isError()).isFalse();
			assertThat(text(result)).contains("delete the route 'books'").contains("confirm=true");
		}).verifyComplete();
		StepVerifier.create(guard.run(false, "delete the route 'books'", this::change))
			.assertNext((result) -> assertThat(text(result)).contains("Nothing was done"))
			.verifyComplete();
		assertThat(this.runs).hasValue(0);
		StepVerifier.create(guard.run(true, "delete the route 'books'", this::change))
			.assertNext((result) -> assertThat(text(result)).isEqualTo("done"))
			.verifyComplete();
		assertThat(this.runs).hasValue(1);
	}

	@Test
	void runsAtOnceWhenConfirmationIsSwitchedOff() {
		StepVerifier.create(new WriteGuard(false).run(null, "whatever", this::change))
			.assertNext((result) -> assertThat(text(result)).isEqualTo("done"))
			.verifyComplete();
		assertThat(this.runs).hasValue(1);
	}

	@Test
	void reportsAFailedChangeAsAnErrorResult() {
		StepVerifier
			.create(new WriteGuard(false).run(true, "whatever",
					() -> Mono.error(new IllegalStateException("database down"))))
			.assertNext((result) -> {
				assertThat(result.isError()).isTrue();
				assertThat(text(result)).contains("database down");
			})
			.verifyComplete();
	}

	private Mono<String> change() {
		return Mono.fromSupplier(() -> {
			this.runs.incrementAndGet();
			return "done";
		});
	}

	private static String text(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

}
