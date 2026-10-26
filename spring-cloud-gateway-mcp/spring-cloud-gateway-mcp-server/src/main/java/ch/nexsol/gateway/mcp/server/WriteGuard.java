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

import java.util.function.Supplier;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import reactor.core.publisher.Mono;

/**
 * Stands between a tool that changes the gateway and the change: unless the call carries
 * {@code confirm: true}, the tool describes what it would do and does nothing. A client
 * that follows the hints asks its user first; one that does not still has to say so
 * explicitly. A confirmation step, not an access control: the caller confirms its own
 * call.
 */
public final class WriteGuard {

	private final boolean confirmWrites;

	public WriteGuard(boolean confirmWrites) {
		this.confirmWrites = confirmWrites;
	}

	/**
	 * Run a change, or describe it when it is not confirmed.
	 * @param confirm the {@code confirm} argument of the call, {@code null} when absent
	 * @param change what the call would change, as a sentence
	 * @param action the change, answering with what was done
	 * @return the result for the client
	 */
	public Mono<CallToolResult> run(Boolean confirm, String change, Supplier<Mono<String>> action) {
		if (this.confirmWrites && !Boolean.TRUE.equals(confirm)) {
			return Mono.just(text("This call changes the gateway: " + change
					+ ". Nothing was done. Call it again with confirm=true to proceed."));
		}
		return Mono.defer(action)
			.map(WriteGuard::text)
			.onErrorResume((ex) -> Mono.just(CallToolResult.builder()
				.isError(true)
				.addTextContent(ex.getClass().getSimpleName() + ": " + ex.getMessage())
				.build()));
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addTextContent(text).isError(false).build();
	}

}
