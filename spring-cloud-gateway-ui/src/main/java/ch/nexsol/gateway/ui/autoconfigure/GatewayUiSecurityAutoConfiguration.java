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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ch.nexsol.gateway.ui.security.ClaimRoles;
import ch.nexsol.gateway.ui.security.GatewayUiSecurityProperties;
import ch.nexsol.gateway.ui.security.LoginController;
import ch.nexsol.gateway.ui.security.UiAccessDeniedHandler;
import ch.nexsol.gateway.ui.security.UiAuthenticationEntryPoint;
import ch.nexsol.gateway.ui.security.UiLoginProviders;
import ch.nexsol.gateway.ui.security.UiSecuredPaths;
import ch.nexsol.gateway.ui.security.UiSecurityCustomizer;
import ch.nexsol.gateway.ui.security.UiSecurityModelAttributes;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.util.StringUtils;

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
 * What the chain then does with those paths is the
 * {@link GatewayUiSecurityProperties.Mode mode}. It permits them by default, which is the
 * behaviour the plugin has always had. Set to
 * {@link GatewayUiSecurityProperties.Mode#AUTHENTICATED}, it puts the login page of the
 * console in front of them instead: a local user, an OpenID Connect provider, or both,
 * and a Bearer token for whoever calls the endpoints of the console rather than browsing
 * them.
 * <p>
 * The chain can be turned off with
 * {@code spring.cloud.gateway.server.webflux.ui.security-chain-enabled=false} or replaced
 * by declaring a bean named {@code gatewayUiSecurityWebFilterChain}.
 * <p>
 * Note that, as with any {@link SecurityWebFilterChain} bean, its mere presence makes
 * Spring Boot back off from its default "everything authenticated" chain: an application
 * relying on that default must declare its own chains.
 * <p>
 * The chain is built from a {@code ServerHttpSecurity}, which exists only once WebFlux
 * security is enabled &mdash; what Boot does through its own reactive security
 * auto-configuration. The presence of that auto-configuration is therefore the condition,
 * rather than the bean it declares: that bean is a prototype, and a prototype is not
 * something {@code @ConditionalOnBean} finds.
 */
@AutoConfiguration(after = GatewayUiAutoConfiguration.class,
		afterName = "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration")
@ConditionalOnClass(value = { SecurityWebFilterChain.class, ServerHttpSecurity.class },
		name = "org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration")
@ConditionalOnProperty(prefix = "spring.cloud.gateway.server.webflux.ui", name = "security-chain-enabled",
		matchIfMissing = true)
@EnableConfigurationProperties(GatewayUiSecurityProperties.class)
public class GatewayUiSecurityAutoConfiguration {

	/**
	 * Order of the contributed chain: ahead of the chains an application usually declares
	 * from {@code 1}, so the UI paths are served before any catch-all rule.
	 */
	public static final int GATEWAY_UI_CHAIN_ORDER = Ordered.HIGHEST_PRECEDENCE + 300;

	/**
	 * Path of the login page, which is also the path its form posts to.
	 */
	public static final String LOGIN_PATH = "/ui/login";

	/**
	 * Path the sidebar posts to in order to end the session.
	 */
	public static final String LOGOUT_PATH = "/ui/logout";

	/**
	 * Path of the page telling a signed-in visitor that the console is not theirs to
	 * reach.
	 */
	public static final String FORBIDDEN_PATH = "/ui/forbidden";

	private static final String SECURITY_PREFIX = "spring.cloud.gateway.server.webflux.ui.security";

	/**
	 * Registers the chain over the exact paths the active views serve.
	 * @param http the reactive security builder
	 * @param securedPaths the paths contributed by the active views
	 * @param properties the security configuration of the console
	 * @param customizers the contributions of the OAuth2 modules that are on the
	 * classpath
	 * @param userDetailsService the user directory of the application, if it declared one
	 * @param authenticationManager the authentication manager of the application, if it
	 * declared one
	 * @return the gateway UI security filter chain
	 */
	@Bean
	@Order(GATEWAY_UI_CHAIN_ORDER)
	@ConditionalOnBean(UiSecuredPaths.class)
	@ConditionalOnMissingBean(name = "gatewayUiSecurityWebFilterChain")
	SecurityWebFilterChain gatewayUiSecurityWebFilterChain(ServerHttpSecurity http,
			ObjectProvider<UiSecuredPaths> securedPaths, GatewayUiSecurityProperties properties,
			ObjectProvider<UiSecurityCustomizer> customizers,
			ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager) {
		List<String> paths = securedPaths.orderedStream()
			.flatMap((contribution) -> contribution.paths().stream())
			.distinct()
			.collect(Collectors.toCollection(ArrayList::new));
		http.cors(withDefaults());
		if (properties.getMode() != GatewayUiSecurityProperties.Mode.AUTHENTICATED) {
			http.csrf(ServerHttpSecurity.CsrfSpec::disable);
			http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(paths.toArray(String[]::new)));
			http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
			return http.build();
		}
		// The endpoints of an authentication exchange are reached before there is a
		// principal, so they join the paths of the views rather than the other way round.
		List<UiSecurityCustomizer> contributions = customizers.orderedStream().toList();
		contributions.stream()
			.flatMap((contribution) -> contribution.paths().stream())
			.filter((path) -> !paths.contains(path))
			.forEach(paths::add);
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(paths.toArray(String[]::new)));
		http.authorizeExchange((spec) -> {
			spec.pathMatchers(openPaths(paths)).permitAll();
			// Ending the session and being told why the console is closed are the two
			// things
			// a signed-in visitor must be able to do whatever their roles: gating them on
			// the roles would trap someone who holds none, unable even to sign out.
			spec.pathMatchers(LOGOUT_PATH, FORBIDDEN_PATH).authenticated();
			List<String> roles = properties.getRequiredRoles();
			if (roles.isEmpty()) {
				spec.anyExchange().authenticated();
			}
			else {
				spec.anyExchange().hasAnyRole(roles.toArray(String[]::new));
			}
		});
		// A console signed into through a provider alone has nothing behind a credentials
		// form. Configuring one anyway leaves Spring Security without an authentication
		// manager to build its filter from, and the application does not start.
		if (credentialsForm(properties, userDetailsService, authenticationManager)) {
			http.formLogin((form) -> {
				form.loginPage(LOGIN_PATH);
				form.authenticationSuccessHandler(successHandler());
			});
			localUser(properties.getUser()).ifPresent(http::authenticationManager);
		}
		http.logout((logout) -> {
			logout.requiresLogout(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, LOGOUT_PATH));
			logout.logoutSuccessHandler(logoutSuccessHandler());
		});
		http.exceptionHandling((handling) -> {
			handling.authenticationEntryPoint(new UiAuthenticationEntryPoint(LOGIN_PATH));
			handling.accessDeniedHandler(new UiAccessDeniedHandler(FORBIDDEN_PATH));
		});
		contributions.forEach((contribution) -> contribution.customize(http));
		return http.build();
	}

	/**
	 * Declares the paths the login exchange itself serves, so the chain matches them like
	 * any other path of the console.
	 * @return the login and logout paths
	 */
	@Bean
	@ConditionalOnProperty(prefix = SECURITY_PREFIX, name = "mode", havingValue = "authenticated")
	UiSecuredPaths loginSecuredPaths() {
		return new UiSecuredPaths(LOGIN_PATH, LOGOUT_PATH, FORBIDDEN_PATH);
	}

	/**
	 * Registers the controller serving the login page.
	 * @param providers the identity providers to offer a button for, absent when no
	 * OAuth2 client is registered
	 * @param userDetailsService the user directory of the application, if it declared one
	 * @param authenticationManager the authentication manager of the application, if it
	 * declared one
	 * @param properties the security configuration of the console
	 * @return the login controller
	 */
	@Bean
	@ConditionalOnProperty(prefix = SECURITY_PREFIX, name = "mode", havingValue = "authenticated")
	@ConditionalOnMissingBean
	LoginController gatewayUiLoginController(ObjectProvider<UiLoginProviders> providers,
			ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager,
			GatewayUiSecurityProperties properties) {
		UiLoginProviders available = providers.getIfAvailable();
		return new LoginController((available != null) ? available.providers() : Map.of(),
				credentialsForm(properties, userDetailsService, authenticationManager));
	}

	/**
	 * Whether there is anything for a credentials form to authenticate against: the local
	 * user of the console, or whatever the application declared. Showing a form that
	 * cannot succeed is worse than showing none, and configuring one leaves Spring
	 * Security without an authentication manager altogether.
	 */
	private static boolean credentialsForm(GatewayUiSecurityProperties properties,
			ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager) {
		return StringUtils.hasText(properties.getUser().getPassword())
				|| userDetailsService.stream().findAny().isPresent()
				|| authenticationManager.stream().findAny().isPresent();
	}

	/**
	 * Registers the advice publishing the CSRF token and the signed-in principal to the
	 * templates of the shell.
	 * @return the advice
	 */
	@Bean
	@ConditionalOnProperty(prefix = SECURITY_PREFIX, name = "mode", havingValue = "authenticated")
	@ConditionalOnMissingBean
	UiSecurityModelAttributes gatewayUiSecurityModelAttributes() {
		return new UiSecurityModelAttributes();
	}

	/**
	 * The paths reachable without a principal: the static assets, which the login page
	 * itself loads, and the login page. Everything the console serves under {@code /ui}
	 * is behind the login.
	 */
	private static String[] openPaths(List<String> paths) {
		return Stream
			.concat(paths.stream().filter((path) -> !path.equals("/ui") && !path.startsWith("/ui/")),
					Stream.of(LOGIN_PATH))
			.distinct()
			.toArray(String[]::new);
	}

	/**
	 * Signing in resumes the navigation it interrupted: the chain saved the page the
	 * visitor was heading for, and this reads it back. It falls on the home page of the
	 * console rather than the root of the application, which the gateway serves for
	 * something else entirely.
	 */
	private static ServerAuthenticationSuccessHandler successHandler() {
		RedirectServerAuthenticationSuccessHandler handler = new RedirectServerAuthenticationSuccessHandler("/ui");
		handler.setRequestCache(new WebSessionServerRequestCache());
		return handler;
	}

	private static ServerLogoutSuccessHandler logoutSuccessHandler() {
		RedirectServerLogoutSuccessHandler handler = new RedirectServerLogoutSuccessHandler();
		handler.setLogoutSuccessUrl(URI.create(LOGIN_PATH + "?logout"));
		return handler;
	}

	/**
	 * The local user, held by this chain alone rather than published as a
	 * {@code ReactiveUserDetailsService}: the console gets a way in of its own without
	 * competing with the authentication the rest of the application is built on. Left
	 * without a password, there is no local user and the chain authenticates against
	 * whatever the application configured.
	 */
	private static Optional<ReactiveAuthenticationManager> localUser(GatewayUiSecurityProperties.User user) {
		if (!StringUtils.hasText(user.getPassword())) {
			return Optional.empty();
		}
		PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
		// A password already carrying the id of the encoder that produced it is taken as
		// is,
		// which is how a deployment keeps the clear text out of its configuration.
		String password = user.getPassword().startsWith("{") ? user.getPassword() : encoder.encode(user.getPassword());
		UserDetails details = User.withUsername(user.getName())
			.password(password)
			.roles(user.getRoles().toArray(String[]::new))
			.build();
		UserDetailsRepositoryReactiveAuthenticationManager manager = new UserDetailsRepositoryReactiveAuthenticationManager(
				new MapReactiveUserDetailsService(details));
		manager.setPasswordEncoder(encoder);
		return Optional.of(manager);
	}

	/**
	 * Contributes the OpenID Connect login to the chain, and the buttons the login page
	 * offers it under. Both back off when the application registered no client: the
	 * module being on the classpath does not mean a provider was configured.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ReactiveClientRegistrationRepository.class)
	@ConditionalOnProperty(prefix = SECURITY_PREFIX, name = "mode", havingValue = "authenticated")
	static class OAuth2LoginConfiguration {

		/**
		 * Contributes the OpenID Connect login to the chain of the console.
		 * @param registrations the client registrations of the application, if it has any
		 * @return the contribution, which does nothing when no client is registered
		 */
		@Bean
		UiSecurityCustomizer gatewayUiOAuth2LoginCustomizer(
				ObjectProvider<ReactiveClientRegistrationRepository> registrations) {
			return new UiSecurityCustomizer() {

				@Override
				public void customize(ServerHttpSecurity http) {
					ReactiveClientRegistrationRepository repository = registrations.getIfAvailable();
					if (repository == null) {
						return;
					}
					http.oauth2Login((oauth2) -> {
						// Without it, Spring Security generates a provider chooser of its
						// own at /login and the console would have two login pages.
						oauth2.loginPage(LOGIN_PATH);
						oauth2.authenticationSuccessHandler(successHandler());
						oauth2.authenticationFailureHandler(
								new RedirectServerAuthenticationFailureHandler(LOGIN_PATH + "?error_oauth2"));
					});
					http.logout((logout) -> logout.logoutSuccessHandler(providerLogoutHandler(repository)));
				}

				@Override
				public List<String> paths() {
					return (registrations.getIfAvailable() != null)
							? List.of("/oauth2/authorization/**", "/login/oauth2/code/**") : List.of();
				}

			};
		}

		/**
		 * Ends the session the provider holds, not only the one the console holds.
		 * Without it, signing out and signing back in hands the same account straight
		 * back: the provider still considers the browser signed in, so nobody can come
		 * back as somebody else without clearing their cookies.
		 * <p>
		 * The handler only takes the provider route for a principal that came from it,
		 * and only when the provider published an {@code end_session_endpoint}. A local
		 * user, or a registration configured endpoint by endpoint rather than from an
		 * issuer, falls back to the plain redirect &mdash; which is why this one handler
		 * replaces the other rather than sitting next to it.
		 * <p>
		 * Note that this ends the single sign-on session itself: an operator signing out
		 * of the console signs out of whatever else shares that session with the
		 * provider.
		 */
		private static ServerLogoutSuccessHandler providerLogoutHandler(
				ReactiveClientRegistrationRepository registrations) {
			OidcClientInitiatedServerLogoutSuccessHandler handler = new OidcClientInitiatedServerLogoutSuccessHandler(
					registrations);
			handler.setPostLogoutRedirectUri("{baseUrl}" + LOGIN_PATH + "?logout");
			handler.setLogoutSuccessUrl(URI.create(LOGIN_PATH + "?logout"));
			return handler;
		}

		/**
		 * Resolves the registrations to show once, at start-up: only a repository holding
		 * them in memory can be enumerated, and one resolving issuers dynamically offers
		 * nothing to list.
		 * @param registrations the client registrations of the application, if it has any
		 * @return the providers the login page offers a button for
		 */
		@Bean
		@ConditionalOnMissingBean
		UiLoginProviders gatewayUiLoginProviders(ObjectProvider<ReactiveClientRegistrationRepository> registrations) {
			Map<String, String> providers = new LinkedHashMap<>();
			if (registrations.getIfAvailable() instanceof Iterable<?> iterable) {
				for (Object candidate : iterable) {
					if (candidate instanceof ClientRegistration registration) {
						providers.put(registration.getRegistrationId(), registration.getClientName());
					}
				}
			}
			return new UiLoginProviders(providers);
		}

		/**
		 * Adds the roles of the identity token to the authorities of the signed-in user,
		 * so that {@code required-roles} means the same thing whether the principal came
		 * from the provider or from a Bearer token. Only declared when a claim was
		 * configured: the bean applies to the whole application, and taking it over for
		 * nothing would be rude.
		 * @param properties the security configuration of the console
		 * @return the user service adding the roles of the identity token
		 */
		@Bean
		@ConditionalOnMissingBean
		@ConditionalOnProperty(prefix = SECURITY_PREFIX, name = "roles-claim")
		ReactiveOAuth2UserService<OidcUserRequest, OidcUser> gatewayUiOidcUserService(
				GatewayUiSecurityProperties properties) {
			OidcReactiveOAuth2UserService delegate = new OidcReactiveOAuth2UserService();
			String claim = properties.getRolesClaim();
			return (request) -> delegate.loadUser(request).map((user) -> {
				Collection<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
				authorities.addAll(ClaimRoles.from(user.getClaims(), claim));
				// The claim naming the principal has to be carried over: rebuilding the
				// user
				// without it falls back on 'sub', and the side menu of the console would
				// read the opaque identifier of the provider rather than a person.
				String nameAttribute = request.getClientRegistration()
					.getProviderDetails()
					.getUserInfoEndpoint()
					.getUserNameAttributeName();
				return StringUtils.hasText(nameAttribute)
						? new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), nameAttribute)
						: new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo());
			});
		}

	}

	/**
	 * Contributes the resource server to the chain, so the endpoints of the console
	 * answer a Bearer token as well as a session. It backs off when the application
	 * configured no issuer, since there would be no decoder to validate the token with.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ReactiveJwtDecoder.class)
	@ConditionalOnProperty(prefix = SECURITY_PREFIX, name = "mode", havingValue = "authenticated")
	static class OAuth2ResourceServerConfiguration {

		/**
		 * Contributes the resource server to the chain of the console.
		 * @param decoder the decoder validating the tokens, if the application configured
		 * an issuer
		 * @param properties the security configuration of the console
		 * @return the contribution, which does nothing when there is no decoder
		 */
		@Bean
		UiSecurityCustomizer gatewayUiResourceServerCustomizer(ObjectProvider<ReactiveJwtDecoder> decoder,
				GatewayUiSecurityProperties properties) {
			return (http) -> {
				if (decoder.getIfAvailable() == null) {
					return;
				}
				http.oauth2ResourceServer((oauth2) -> oauth2.jwt((jwt) -> {
					String claim = properties.getRolesClaim();
					if (StringUtils.hasText(claim)) {
						jwt.jwtAuthenticationConverter(rolesConverter(claim));
					}
				}));
			};
		}

		/**
		 * Keeps the scope authorities the default converter produces and adds the roles
		 * read from the configured claim, so a token may be matched on either.
		 */
		private static ReactiveJwtAuthenticationConverter rolesConverter(String claim) {
			JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
			ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
			converter.setJwtGrantedAuthoritiesConverter((jwt) -> {
				Collection<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
				authorities.addAll(ClaimRoles.from(jwt.getClaims(), claim));
				return Flux.fromIterable(authorities);
			});
			return converter;
		}

	}

}
