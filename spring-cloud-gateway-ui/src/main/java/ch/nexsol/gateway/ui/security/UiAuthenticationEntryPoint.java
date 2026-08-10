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

import reactor.core.publisher.Mono;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;

/**
 * Sends an unauthenticated visitor of the console to the login page, in the terms of the
 * request that was made.
 * <p>
 * A page navigation is redirected. An HTMX fragment cannot be: the browser would swap the
 * login page into a corner of the shell, so it answers a {@code 401} carrying the
 * {@code HX-Redirect} header HTMX turns into a full page load. A subscription to an event
 * stream answers a plain {@code 401}, which is what an {@code EventSource} can act on
 * &mdash; served the login page instead, it would keep reconnecting to it. So does a
 * request that carried an {@code Authorization} header: a client presenting a token asked
 * an API a question, and an HTML login page is not an answer to it.
 */
public class UiAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

	private static final String HTMX_REQUEST_HEADER = "HX-Request";

	private static final String HTMX_REDIRECT_HEADER = "HX-Redirect";

	private final String loginPage;

	private final ServerAuthenticationEntryPoint redirect;

	/**
	 * Creates the entry point sending visitors to the given login page.
	 * @param loginPage the path of the login page
	 */
	public UiAuthenticationEntryPoint(String loginPage) {
		this.loginPage = loginPage;
		this.redirect = new RedirectServerAuthenticationEntryPoint(loginPage);
	}

	@Override
	public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
		ServerHttpRequest request = exchange.getRequest();
		if (request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) {
			exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
			exchange.getResponse().getHeaders().add(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
			return exchange.getResponse().setComplete();
		}
		if (request.getHeaders().getAccept().contains(MediaType.TEXT_EVENT_STREAM)) {
			exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
			return exchange.getResponse().setComplete();
		}
		if (request.getHeaders().getFirst(HTMX_REQUEST_HEADER) != null) {
			exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
			exchange.getResponse().getHeaders().add(HTMX_REDIRECT_HEADER, this.loginPage);
			return exchange.getResponse().setComplete();
		}
		return this.redirect.commence(exchange, exception);
	}

}
