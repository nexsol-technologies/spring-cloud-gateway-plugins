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

import java.security.Principal;

import reactor.core.publisher.Mono;

import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.server.ServerWebExchange;

/**
 * Exposes to every server-rendered view what the shell needs once the console
 * authenticates: the CSRF token its forms and HTMX requests have to carry, and the name
 * of the signed-in principal the side menu shows.
 * <p>
 * Both are published as reactive attributes, resolved before the template renders. Either
 * can be absent &mdash; the login page has no principal yet &mdash; and the templates
 * guard on that rather than assuming a session.
 */
@ControllerAdvice
public class UiSecurityModelAttributes {

	/**
	 * Adds the CSRF token of the exchange to the model.
	 * @param exchange the current exchange
	 * @return the token, empty when CSRF protection is not active on this exchange
	 */
	@ModelAttribute("_csrf")
	public Mono<CsrfToken> csrfToken(ServerWebExchange exchange) {
		Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
		return (token != null) ? token : Mono.empty();
	}

	/**
	 * Adds the name of the signed-in principal to the model.
	 * @param exchange the current exchange
	 * @return the principal name, empty when the exchange is not authenticated
	 */
	@ModelAttribute("currentUser")
	public Mono<String> currentUser(ServerWebExchange exchange) {
		return exchange.getPrincipal().map(Principal::getName);
	}

}
