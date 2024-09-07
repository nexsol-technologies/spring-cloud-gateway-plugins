package ch.nexsol.gateway.sample;

import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

import static org.springframework.security.config.Customizer.withDefaults;

@SpringBootApplication
public class ApiGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiGatewayApplication.class, args);
	}

	@Bean
	@Order(1)
	SecurityWebFilterChain basicWebFilterChain(ServerHttpSecurity http) {
		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/test-authorization/*"));
		http.authorizeExchange((spec) -> {
			spec.anyExchange().authenticated();
		});
		http.httpBasic(withDefaults());
		http.formLogin(ServerHttpSecurity.FormLoginSpec::disable);
		http.logout(ServerHttpSecurity.LogoutSpec::disable);
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityWebFilterChain oauthWebFilterChain(ServerHttpSecurity http) {

		http.cors(withDefaults());
		http.csrf(ServerHttpSecurity.CsrfSpec::disable);
		http.authorizeExchange((spec) -> {
			spec.pathMatchers("/actuator/**").permitAll();
			spec.anyExchange().authenticated();
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
