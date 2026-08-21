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

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.StringUtils;
import org.springframework.web.server.session.CookieWebSessionIdResolver;

/**
 * Names the session cookie of the console after the console, instead of leaving it on the
 * {@code SESSION} every Spring application uses.
 * <p>
 * A gateway is the one host that cannot afford that name. It answers on the same origin
 * as the services it routes to, so a {@code Set-Cookie: SESSION=} coming back from any of
 * them lands on the browser as the cookie of the console and takes the operator's session
 * with it &mdash; and the console hands its own {@code SESSION} to those services on
 * every routed request, where a session store shared with them can make the two collide
 * the other way round. Under its own name neither can happen.
 * <p>
 * The resolver Spring Boot builds is decorated rather than replaced, so everything else
 * it reads from {@code server.reactive.session.cookie} still applies, and an application
 * that names the cookie itself through that same property keeps its name.
 * <p>
 * Note that the resolver belongs to the application, not to a filter chain: a gateway
 * using sessions for something other than its console renames that cookie too.
 */
public class UiSessionCookieName implements BeanPostProcessor {

	/**
	 * Name the console gives its session cookie.
	 */
	public static final String COOKIE_NAME = "GATEWAY_CONSOLE_SESSION";

	private final String configuredName;

	/**
	 * Creates the post-processor.
	 * @param configuredName the name the application set through
	 * {@code server.reactive.session.cookie.name}, empty when it set none
	 */
	public UiSessionCookieName(String configuredName) {
		this.configuredName = configuredName;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof CookieWebSessionIdResolver resolver && !StringUtils.hasText(this.configuredName)) {
			resolver.setCookieName(COOKIE_NAME);
		}
		return bean;
	}

}
