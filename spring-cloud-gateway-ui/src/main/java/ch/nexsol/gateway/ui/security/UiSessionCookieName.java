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
 * {@code SESSION} every Spring application uses, and gives it the {@code SameSite}
 * attribute Spring Boot leaves off.
 * <p>
 * A gateway is the one host that cannot afford that name. It answers on the same origin
 * as the services it routes to, so a {@code Set-Cookie: SESSION=} coming back from any of
 * them lands on the browser as the cookie of the console and takes the operator's session
 * with it &mdash; and the console hands its own {@code SESSION} to those services on
 * every routed request, where a session store shared with them can make the two collide
 * the other way round. Under its own name neither can happen.
 * <p>
 * {@code SameSite} is set because nobody else sets it. {@code CookieWebSessionIdResolver}
 * defaults it to {@code Lax}, but Spring Boot's cookie initializer applies
 * {@code server.reactive.session.cookie.same-site} unconditionally, so a property nobody
 * configured wipes that default and the cookie goes out with no {@code SameSite} at all.
 * <p>
 * The resolver Spring Boot builds is decorated rather than replaced, so everything else
 * it reads from {@code server.reactive.session.cookie} still applies, and an application
 * that sets the name or the {@code SameSite} itself through those properties keeps what
 * it set.
 * <p>
 * Note that the resolver belongs to the application, not to a filter chain: a gateway
 * using sessions for something other than its console renames that cookie too.
 */
public class UiSessionCookieName implements BeanPostProcessor {

	/**
	 * Name the console gives its session cookie.
	 */
	public static final String COOKIE_NAME = "GATEWAY_CONSOLE_SESSION";

	/**
	 * {@code SameSite} the console gives its session cookie: the value the framework
	 * itself defaults to, and the strictest one a sign-in redirect coming back from an
	 * OpenID Connect provider survives.
	 */
	public static final String SAME_SITE = "Lax";

	private final String configuredName;

	private final String configuredSameSite;

	/**
	 * Creates the post-processor.
	 * @param configuredName the name the application set through
	 * {@code server.reactive.session.cookie.name}, empty when it set none
	 * @param configuredSameSite the attribute the application set through
	 * {@code server.reactive.session.cookie.same-site}, empty when it set none
	 */
	public UiSessionCookieName(String configuredName, String configuredSameSite) {
		this.configuredName = configuredName;
		this.configuredSameSite = configuredSameSite;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof CookieWebSessionIdResolver resolver) {
			if (!StringUtils.hasText(this.configuredName)) {
				resolver.setCookieName(COOKIE_NAME);
			}
			// Order is what makes this work: cookie initializers run in the order
			// they were added, and Spring Boot adds its own while building the bean,
			// so the post-processor is the one place from which this one lands last.
			if (!StringUtils.hasText(this.configuredSameSite)) {
				resolver.addCookieInitializer((cookie) -> cookie.sameSite(SAME_SITE));
			}
		}
		return bean;
	}

}
