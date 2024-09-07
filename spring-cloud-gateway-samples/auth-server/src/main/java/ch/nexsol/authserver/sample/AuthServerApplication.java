package ch.nexsol.authserver.sample;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@SpringBootApplication
public class AuthServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuthServerApplication.class, args);
	}

	@Bean
	BCryptPasswordEncoder bCryptPasswordEncoder() {
		return new BCryptPasswordEncoder(5);
	}

	@Bean
	InMemoryUserDetailsManager userDetailsService(BCryptPasswordEncoder cryptPasswordEncoder) {

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

		InMemoryUserDetailsManager userDetailsManager = new InMemoryUserDetailsManager(users);
		return userDetailsManager;
	}

	@Bean
	OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
		return (context) -> {
			if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
				context.getClaims().claims((claims) -> {
					Set<String> roles = AuthorityUtils.authorityListToSet(context.getPrincipal().getAuthorities())
						.stream()
						.map(c -> c.replaceFirst("^ROLE_", ""))
						.collect(Collectors.collectingAndThen(Collectors.toSet(), Collections::unmodifiableSet));
					claims.put("roles", roles);
				});
			}
		};
	}

}
