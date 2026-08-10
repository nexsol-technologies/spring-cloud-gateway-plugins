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

package ch.nexsol.gateway.ui.security;

import java.net.URI;

import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;

/**
 * Tells a signed-in visitor that the console is not theirs to reach, rather than dropping
 * a bare {@code 403} on them.
 * <p>
 * A visitor turned away by {@code required-roles} is authenticated: signing in again
 * would change nothing, and the browser page of a naked {@code 403} leaves them with no
 * way even to sign out. A page navigation is therefore sent to the page that says so and
 * carries that way out.
 * <p>
 * Only a navigation is redirected. Everything else keeps the status it earned: this
 * handler also catches a rejected CSRF token, which never arrives on a {@code GET}, and
 * answering a write with a redirect to an explanation page would hide it.
 */
public class UiAccessDeniedHandler implements ServerAccessDeniedHandler {

	private static final String HTMX_REQUEST_HEADER = "HX-Request";

	private static final String HTMX_REDIRECT_HEADER = "HX-Redirect";

	private final String forbiddenPage;

	/**
	 * Creates the handler sending visitors to the given page.
	 * @param forbiddenPage the path of the page explaining the refusal
	 */
	public UiAccessDeniedHandler(String forbiddenPage) {
		this.forbiddenPage = forbiddenPage;
	}

	@Override
	public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
		if (exchange.getRequest().getHeaders().getFirst(HTMX_REQUEST_HEADER) != null) {
			exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
			exchange.getResponse().getHeaders().add(HTMX_REDIRECT_HEADER, this.forbiddenPage);
			return exchange.getResponse().setComplete();
		}
		if (navigation(exchange)) {
			return new DefaultServerRedirectStrategy().sendRedirect(exchange, URI.create(this.forbiddenPage));
		}
		exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
		return exchange.getResponse().setComplete();
	}

	/**
	 * Whether this is a browser asking for a page. A subscription to an event stream and
	 * a request carrying a token are both {@code GET} requests that an explanation page
	 * is no answer to.
	 */
	private static boolean navigation(ServerWebExchange exchange) {
		HttpHeaders headers = exchange.getRequest().getHeaders();
		return HttpMethod.GET.equals(exchange.getRequest().getMethod())
				&& !headers.getAccept().contains(MediaType.TEXT_EVENT_STREAM)
				&& headers.getFirst(HttpHeaders.AUTHORIZATION) == null;
	}

}
