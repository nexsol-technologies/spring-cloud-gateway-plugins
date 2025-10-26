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

package ch.nexsol.gateway.sample;

import java.util.List;

import ch.nexsol.gateway.oauth2.filter.webfilter.BasicAuthExchangeToAccessTokenGatewayWebFilter;
import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;
import ch.nexsol.gateway.oauth2.utils.SecurityUtils;
import reactor.core.publisher.Hooks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import static org.springframework.security.config.Customizer.withDefaults;

@SpringBootApplication
public class ApiGatewayApplication {

	public static void main(String[] args) {
		Hooks.onOperatorDebug();
		SpringApplication.run(ApiGatewayApplication.class, args);
	}

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	@RefreshScope
	CorsWebFilter corsWebFilter(CorsConfigurationSource corsConfigurationSource) {
		return new CorsWebFilter(corsConfigurationSource);
	}

	@Bean
	@RefreshScope
	CorsConfigurationSource corsConfigurationSource(GlobalCorsProperties globalCorsProperties) {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		globalCorsProperties.getCorsConfigurations().forEach(source::registerCorsConfiguration);
		return source;
	}

	@Bean
	@Order(1)
	@Conditional(ch.nexsol.gateway.oauth2.filter.webfilter.condition.BasicAuthExchangeConfiguredCondition.class)
	SecurityWebFilterChain basicWebFilterChain(ServerHttpSecurity http,
			BasicAuthExchangeToAccessTokenProperties properties,
			BasicAuthExchangeToAccessTokenGatewayWebFilter basicAuthExchangeGatewayWebFilter) {
		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.securityMatcher(SecurityUtils.authorizationHeaderBasicMatcher(properties));
		http.httpBasic(ServerHttpSecurity.HttpBasicSpec::disable);
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		http.addFilterBefore(basicAuthExchangeGatewayWebFilter, SecurityWebFiltersOrder.AUTHENTICATION);
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityWebFilterChain basicWebFilterChain(ServerHttpSecurity http) {
		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/test-authorization/*"));
		http.authorizeExchange((spec) -> spec.anyExchange().authenticated());
		http.httpBasic(withDefaults());
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		return http.build();
	}

	@Bean
	@Order(3)
	SecurityWebFilterChain oauthWebFilterChain(ServerHttpSecurity http) {

		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.authorizeExchange((spec) -> {
			spec.pathMatchers("/actuator/**").permitAll();
			spec.pathMatchers("/ui/**").permitAll();
			spec.pathMatchers("/api/gateway/**").permitAll();
			spec.pathMatchers("/v3/api-docs", "/v3/api-docs/**", "/configuration/ui", "/swagger-resources/**",
					"/configuration/security", "/swagger-ui.html", "/webjars/**", "/v3/api-docs/swagger-config")
				.permitAll();
			spec.anyExchange().permitAll();
		});
		http.oauth2ResourceServer((oauth2) -> oauth2.jwt(Customizer.withDefaults()));
		return http.build();
	}

	@Bean
	BCryptPasswordEncoder bCryptPasswordEncoder() {
		return new BCryptPasswordEncoder(5);
	}

	@Bean
	MapReactiveUserDetailsService userDetailsService(BCryptPasswordEncoder cryptPasswordEncoder) {

		List<UserDetails> users = List.of(
				User.withUsername("user")
					.passwordEncoder(cryptPasswordEncoder::encode)
					.password("user")
					.roles("READ")
					.build(),
				User.withUsername("admin")
					.passwordEncoder(cryptPasswordEncoder::encode)
					.password("admin")
					.roles("ADMIN")
					.build());

		MapReactiveUserDetailsService userDetailsManager = new MapReactiveUserDetailsService(users);
		return userDetailsManager;
	}

}
