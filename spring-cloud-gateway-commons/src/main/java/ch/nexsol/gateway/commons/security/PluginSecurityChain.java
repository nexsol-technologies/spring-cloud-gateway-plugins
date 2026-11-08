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

package ch.nexsol.gateway.commons.security;

import java.util.Optional;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.util.ClassUtils;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * What every plugin chain does with the credentials it is given, so that the three API
 * plugins answer the same credentials the same way. The matcher and the authorization
 * rules stay with each plugin: they are what differs.
 * <p>
 * Every path such a chain matches is called with a token by a client holding no session
 * and therefore no CSRF token to send back, so the protection is off. The page over the
 * same data, when there is one, is a view of the console, which keeps the protection its
 * forms carry.
 * <p>
 * This class names no JWT class, so a plugin calling it needs neither
 * {@code spring-security-oauth2-jose} nor {@code spring-security-oauth2-resource-server}
 * on its classpath: the Bearer support lives in a class of its own, loaded only once both
 * are found there.
 */
public final class PluginSecurityChain {

	private static final String JWT_DECODER = "org.springframework.security.oauth2.jwt.ReactiveJwtDecoder";

	private static final String RESOURCE_SERVER = "org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager";

	private PluginSecurityChain() {
	}

	/**
	 * Configures HTTP Basic over the local user, else over the user directory or the
	 * authentication manager of the application, and turns off what an API never serves:
	 * CSRF, the form login and the logout.
	 * <p>
	 * Basic is only configured with something to check the credentials against: with no
	 * authentication manager, Spring Security cannot build its filter and the application
	 * does not start.
	 * @param http the chain under construction
	 * @param user the local user of the plugin
	 * @param userDetailsService the user directory of the application, if it declared one
	 * @param authenticationManager the authentication manager of the application, if it
	 * declared one
	 */
	public static void basic(ServerHttpSecurity http, PluginSecurityProperties.User user,
			ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager) {
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		Optional<ReactiveAuthenticationManager> localUser = LocalUserAuthentication.manager(user);
		localUser.ifPresent(http::authenticationManager);
		if (localUser.isPresent() || userDetailsService.getIfAvailable() != null
				|| authenticationManager.getIfAvailable() != null) {
			http.httpBasic(withDefaults());
		}
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
	}

	/**
	 * Configures the resource server over the issuer of the plugin, else over the
	 * {@code ReactiveJwtDecoder} the application declared. With neither, or without the
	 * resource server and JOSE jars on the classpath, no Bearer token is accepted.
	 * @param http the chain under construction
	 * @param jwt the issuer and the audiences of the plugin
	 * @param beanFactory the bean factory the decoder of the application is looked up in
	 * @return whether the chain accepts Bearer tokens
	 */
	public static boolean bearer(ServerHttpSecurity http, PluginSecurityProperties.Jwt jwt, BeanFactory beanFactory) {
		ClassLoader classLoader = (beanFactory instanceof ConfigurableBeanFactory configurable)
				? configurable.getBeanClassLoader() : ClassUtils.getDefaultClassLoader();
		if (!ClassUtils.isPresent(JWT_DECODER, classLoader) || !ClassUtils.isPresent(RESOURCE_SERVER, classLoader)) {
			return false;
		}
		// Resolved here and nowhere above, so that this class links without the JWT jars.
		return PluginBearerSupport.configure(http, jwt, beanFactory);
	}

}
