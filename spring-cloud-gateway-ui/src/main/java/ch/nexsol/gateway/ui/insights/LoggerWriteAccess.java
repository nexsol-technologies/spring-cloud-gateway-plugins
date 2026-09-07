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

package ch.nexsol.gateway.ui.insights;

import reactor.core.publisher.Mono;

import org.springframework.web.server.ServerWebExchange;

/**
 * Whether the principal behind a request may change a logging level.
 * <p>
 * A contract rather than one class, because Spring Security is optional to this module:
 * {@link RoleLoggerWriteAccess} answers the question where it is present, and
 * {@link #denied()} answers it where it is not. An application with nothing to
 * authenticate against cannot check a role, and the safe answer to a question that cannot
 * be asked is no.
 */
@FunctionalInterface
public interface LoggerWriteAccess {

	/**
	 * Whether the principal behind this request may change a level.
	 * @param exchange the request being served
	 * @return {@code true} when the write is allowed
	 */
	Mono<Boolean> granted(ServerWebExchange exchange);

	/**
	 * The answer where no principal can be established at all: the loggers view is read
	 * only, and says so by drawing no control.
	 * @return a check that never grants the write
	 */
	static LoggerWriteAccess denied() {
		return (exchange) -> Mono.just(false);
	}

}
