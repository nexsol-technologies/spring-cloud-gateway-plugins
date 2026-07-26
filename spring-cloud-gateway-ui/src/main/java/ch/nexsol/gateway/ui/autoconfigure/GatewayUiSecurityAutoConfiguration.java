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

package ch.nexsol.gateway.ui.autoconfigure;

import ch.nexsol.gateway.ui.security.UiSecuredPaths;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Auto-configuration contributing the security filter chain of the gateway UI when Spring
 * Security is active: without it the shell is behind the authentication of the
 * application, and its pages, HTMX fragments and static assets are all rejected.
 * <p>
 * The chain matches the exact paths every active view declared through
 * {@link UiSecuredPaths}, never a {@code /ui/**} pattern: a gateway route declared under
 * {@code /ui} (say {@code /ui/find_pwd}) must not inherit the UI permissions. A view that
 * is not active contributes nothing, so its path stays closed.
 * <p>
 * The chain can be turned off with
 * {@code spring.cloud.gateway.server.webflux.ui.security-chain-enabled=false} or replaced
 * by declaring a bean named {@code gatewayUiSecurityWebFilterChain}.
 * <p>
 * Note that, as with any {@link SecurityWebFilterChain} bean, its mere presence makes
 * Spring Boot back off from its default "everything authenticated" chain: an application
 * relying on that default must declare its own chains.
 */
@AutoConfiguration(after = GatewayUiAutoConfiguration.class,
		afterName = "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration")
@ConditionalOnClass({ SecurityWebFilterChain.class, ServerHttpSecurity.class })
@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.ui", name = "security-chain-enabled",
		matchIfMissing = true)
public class GatewayUiSecurityAutoConfiguration {

	/**
	 * Order of the contributed chain: ahead of the chains an application usually declares
	 * from {@code 1}, so the UI paths are served before any catch-all rule.
	 */
	public static final int GATEWAY_UI_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 300;

	/**
	 * Registers the chain permitting the exact paths the active views serve.
	 * @param http the reactive security builder
	 * @param securedPaths the paths contributed by the active views
	 * @return the gateway UI security filter chain
	 */
	@Bean
	@Order(GATEWAY_UI_CHAIN_ORDER)
	@ConditionalOnBean({ ServerHttpSecurity.class, UiSecuredPaths.class })
	@ConditionalOnMissingBean(name = "gatewayUiSecurityWebFilterChain")
	SecurityWebFilterChain gatewayUiSecurityWebFilterChain(ServerHttpSecurity http,
			ObjectProvider<UiSecuredPaths> securedPaths) {
		String[] paths = securedPaths.orderedStream()
			.flatMap((contribution) -> contribution.paths().stream())
			.distinct()
			.toArray(String[]::new);
		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(paths));
		http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
		return http.build();
	}

}
