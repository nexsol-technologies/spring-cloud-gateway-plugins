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

package ch.nexsol.gateway.commons;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * Resolves the status an exchange is answered with, for a filter observing it once the
 * chain has run.
 * <p>
 * A filter that observes a failed exchange cannot read that status from the response. The
 * exception handler writes it after the filters have unwound, and until then the response
 * of Reactor Netty reports the {@code 200} it starts with rather than nothing: a rejected
 * request, a request no route serves and an upstream that refused the connection all read
 * as a success. The status is therefore derived from the failure, the way the error
 * handling of Spring Boot derives it.
 */
public final class ExchangeStatus {

	private ExchangeStatus() {
	}

	/**
	 * Resolves the status of an exchange from its response, or from the failure that
	 * ended it.
	 * <p>
	 * A response already committed keeps the status it was sent with: a failure while the
	 * body is streaming changes nothing to what the client received.
	 * @param exchange the exchange observed
	 * @param failure the failure the chain ended with, {@code null} when it completed
	 * @return the status the exchange is answered with, {@code null} when the chain
	 * completed without writing one
	 */
	public static HttpStatusCode of(ServerWebExchange exchange, Throwable failure) {
		ServerHttpResponse response = exchange.getResponse();
		if (failure == null || response.isCommitted()) {
			return response.getStatusCode();
		}
		if (failure instanceof ResponseStatusException responseStatus) {
			return responseStatus.getStatusCode();
		}
		ResponseStatus annotation = AnnotatedElementUtils.findMergedAnnotation(failure.getClass(),
				ResponseStatus.class);
		return (annotation != null) ? annotation.code() : HttpStatus.INTERNAL_SERVER_ERROR;
	}

}
