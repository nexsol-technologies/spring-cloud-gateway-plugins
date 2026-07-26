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

package ch.nexsol.gateway.ui.routes;

import java.security.Principal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import reactor.core.publisher.Mono;

import org.springframework.context.ApplicationContext;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;

/**
 * Exchange handed to the route predicates by the route tester. It carries the synthetic
 * request and a mutable attribute map &mdash; all a predicate reads &mdash; and rejects
 * everything tied to an in-flight call, such as the response or the session.
 * <p>
 * A predicate touching one of those unsupported parts fails loudly; the tester reports
 * the failure against that predicate instead of hiding it.
 */
final class SyntheticServerWebExchange implements ServerWebExchange {

	private final ServerHttpRequest request;

	private final ApplicationContext applicationContext;

	private final Map<String, Object> attributes = new ConcurrentHashMap<>();

	SyntheticServerWebExchange(ServerHttpRequest request, ApplicationContext applicationContext) {
		this.request = request;
		this.applicationContext = applicationContext;
	}

	@Override
	public ServerHttpRequest getRequest() {
		return this.request;
	}

	@Override
	public ServerHttpResponse getResponse() {
		throw new UnsupportedOperationException("The route tester does not produce a response");
	}

	@Override
	public Map<String, Object> getAttributes() {
		return this.attributes;
	}

	@Override
	public Mono<WebSession> getSession() {
		return Mono.error(new UnsupportedOperationException("The route tester has no web session"));
	}

	@Override
	public <T extends Principal> Mono<T> getPrincipal() {
		return Mono.empty();
	}

	@Override
	public Mono<MultiValueMap<String, String>> getFormData() {
		return Mono.just(new LinkedMultiValueMap<>());
	}

	@Override
	public Mono<MultiValueMap<String, Part>> getMultipartData() {
		return Mono.just(new LinkedMultiValueMap<>());
	}

	@Override
	public LocaleContext getLocaleContext() {
		return new SimpleLocaleContext(Locale.getDefault());
	}

	@Override
	public ApplicationContext getApplicationContext() {
		return this.applicationContext;
	}

	@Override
	public boolean isNotModified() {
		return false;
	}

	@Override
	public boolean checkNotModified(Instant lastModified) {
		return false;
	}

	@Override
	public boolean checkNotModified(String etag) {
		return false;
	}

	@Override
	public boolean checkNotModified(String etag, Instant lastModified) {
		return false;
	}

	@Override
	public String transformUrl(String url) {
		return url;
	}

	@Override
	public void addUrlTransformer(Function<String, String> transformer) {
		// No URL rewriting takes place: nothing is rendered from this exchange.
	}

	@Override
	public String getLogPrefix() {
		return "[route-tester] ";
	}

}
