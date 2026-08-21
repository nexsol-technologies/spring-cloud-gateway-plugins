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
import java.util.function.Function;
import java.util.stream.Collectors;

import ch.nexsol.gateway.commons.security.SecuredPathsContribution;
import ch.nexsol.gateway.ui.security.ClaimRoles;
import ch.nexsol.gateway.ui.security.GatewayUiSecurityProperties;
import ch.nexsol.gateway.ui.security.LoginController;
import ch.nexsol.gateway.ui.security.UiAccessDeniedHandler;
import ch.nexsol.gateway.ui.security.UiAuthenticationEntryPoint;
import ch.nexsol.gateway.ui.security.UiLoginProviders;
import ch.nexsol.gateway.ui.security.UiLoginRegistrations;
import ch.nexsol.gateway.ui.security.UiSecuredPaths;
import ch.nexsol.gateway.ui.security.UiSecurityCustomizer;
import ch.nexsol.gateway.ui.security.UiSecurityModelAttributes;
import ch.nexsol.gateway.ui.security.UiSessionCookieName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
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
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.csrf.CsrfWebFilter;
import org.springframework.security.web.server.savedrequest.WebSessionServerRequestCache;
import org.springframework.security.web.server.util.matcher.AndServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.util.StringUtils;
import org.springframework.web.server.session.DefaultWebSessionManager;
import org.springframework.web.server.session.InMemoryWebSessionStore;
import org.springframework.web.server.session.WebSessionManager;

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
 * The other plugins declare the endpoints they serve the same way, through
 * {@link SecuredPathsContribution}, so that a gateway has one place deciding who reaches
 * what rather than one rule per plugin. They are the ones who know their paths; this
 * chain is the one that knows how to authenticate a visitor.
 * <p>
 * What the chain then does with those paths is the
 * {@link GatewayUiSecurityProperties.Mode mode}. It permits them by default, which is the
 * behaviour the plugin has always had. Set to
 * {@link GatewayUiSecurityProperties.Mode#AUTHENTICATED}, it puts the login page of the
 * console in front of them instead: a local user, an OpenID Connect provider, or both,
 * and a Bearer token for whoever calls the endpoints of the console rather than browsing
 * them.
 * <p>
 * Two kinds of path do not follow the mode, because following it would be wrong in one
 * direction or the other. The ones declared open stay open &mdash; the assets the login
 * page paints with could not be behind that same login. The ones declared as changing the
 * gateway stay closed, under {@link GatewayUiSecurityProperties.WriteMode the write
 * mode}: a console published without a login is a decision, an API that reconfigures the
 * routing table without one is an accident.
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

	private static final Logger LOG = LoggerFactory.getLogger(GatewayUiSecurityAutoConfiguration.class);

	/**
	 * Registers the chain over the exact paths the active views and the other plugins
	 * declared.
	 * @param http the reactive security builder
	 * @param securedPaths the paths contributed by the active views and by the plugins
	 * whose endpoints the console governs
	 * @param properties the security configuration of the console
	 * @param customizers the contributions of the OAuth2 modules that are on the
	 * classpath
	 * @param userDetailsService the user directory of the application, if it declared one
	 * @param authenticationManager the authentication manager of the application, if it
	 * declared one
	 * @param sessionManager the session manager of the application, read to say whether
	 * the sessions the console opens would survive a second instance
	 * @return the gateway UI security filter chain
	 */
	@Bean
	@Order(GATEWAY_UI_CHAIN_ORDER)
	// The console's own declaration, not any contribution: a gateway that switched the
	// views off must not end up with a chain built from another plugin's paths alone,
	// which would take Boot's default "everything authenticated" chain away with it.
	@ConditionalOnBean(UiSecuredPaths.class)
	@ConditionalOnMissingBean(name = "gatewayUiSecurityWebFilterChain")
	SecurityWebFilterChain gatewayUiSecurityWebFilterChain(ServerHttpSecurity http,
			ObjectProvider<SecuredPathsContribution> securedPaths, GatewayUiSecurityProperties properties,
			ObjectProvider<UiSecurityCustomizer> customizers,
			ObjectProvider<ReactiveUserDetailsService> userDetailsService,
			ObjectProvider<ReactiveAuthenticationManager> authenticationManager,
			ObjectProvider<WebSessionManager> sessionManager) {
		List<SecuredPathsContribution> declared = securedPaths.orderedStream().toList();
		List<String> paths = collect(declared, SecuredPathsContribution::paths);
		List<String> open = collect(declared, SecuredPathsContribution::openPaths);
		List<String> write = collect(declared, SecuredPathsContribution::writePaths);
		List<String> csrfExempt = collect(declared, SecuredPathsContribution::csrfExemptPaths);
		add(paths, open);
		add(paths, write);
		List<UiSecurityCustomizer> contributions = customizers.orderedStream().toList();
		http.cors(withDefaults());
		if (properties.getMode() != GatewayUiSecurityProperties.Mode.AUTHENTICATED) {
			http.csrf(ServerHttpSecurity.CsrfSpec::disable);
			http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(paths.toArray(String[]::new)));
			// An open console serves no login page, so the only door the chain can put in
			// front of the paths that change the gateway is the one a caller carries
			// itself: credentials, or a Bearer token the resource server validates.
			boolean credentials = credentialsForm(properties, userDetailsService, authenticationManager);
			List<UiSecurityCustomizer> openContributions = contributions.stream()
				.filter(UiSecurityCustomizer::appliesWhenOpen)
				.toList();
			if (write.isEmpty() || !restrictWrite(properties, credentials || !openContributions.isEmpty())) {
				warnOpenWritePaths(properties, write);
				http.authorizeExchange((spec) -> spec.anyExchange().permitAll());
				return http.build();
			}
			http.authorizeExchange((spec) -> {
				// The roles are asked for here too. They are what an operator holds, and
				// an
				// open console is the one place where the only principal that ever has to
				// be checked is the one changing the gateway.
				List<String> roles = properties.getRequiredRoles();
				if (roles.isEmpty()) {
					spec.pathMatchers(write.toArray(String[]::new)).authenticated();
				}
				else {
					spec.pathMatchers(write.toArray(String[]::new)).hasAnyRole(roles.toArray(String[]::new));
				}
				spec.anyExchange().permitAll();
			});
			// Asking for Basic credentials with no authentication manager to check them
			// against leaves Spring Security unable to build the filter, and the
			// application does not start.
			if (credentials) {
				http.httpBasic(withDefaults());
				localUser(properties.getUser()).ifPresent(http::authenticationManager);
			}
			openContributions.forEach((contribution) -> contribution.customize(http));
			return http.build();
		}
		warnInMemorySessions(sessionManager);
		// The endpoints of an authentication exchange are reached before there is a
		// principal, so they join the paths of the views rather than the other way round.
		List<String> exchangePaths = contributions.stream()
			.flatMap((contribution) -> contribution.paths().stream())
			.toList();
		add(paths, exchangePaths);
		add(open, exchangePaths);
		// The login page is reached without a principal by definition. What else stays in
		// front of it was declared open by whoever serves it: the assets the login page
		// paints with, and the endpoints another plugin has polled without credentials.
		add(open, List.of(LOGIN_PATH));
		http.securityMatcher(ServerWebExchangeMatchers.pathMatchers(paths.toArray(String[]::new)));
		exemptFromCsrf(http, csrfExempt);
		http.authorizeExchange((spec) -> {
			spec.pathMatchers(open.toArray(String[]::new)).permitAll();
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
			// The same credentials over Basic, which is the only way a caller with no
			// browser can present them: a script has no form to post and no session to
			// hold, and the endpoints of the console answer it as they answer a Bearer
			// token. Without this the local user is a way in through the page alone,
			// and 'Authorization: Basic' is read by nobody on this chain.
			http.httpBasic(withDefaults());
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
	 * Renames the session cookie of the console, so that a {@code Set-Cookie: SESSION=}
	 * coming back from any service the gateway routes to cannot land on the browser as
	 * the cookie of the console.
	 * @param environment the environment the cookie settings of the application are read
	 * from
	 * @return the post-processor naming the cookie
	 */
	@Bean
	@ConditionalOnProperty(prefix = SECURITY_PREFIX, name = "mode", havingValue = "authenticated")
	static UiSessionCookieName gatewayUiSessionCookieName(Environment environment) {
		return new UiSessionCookieName(environment.getProperty("server.reactive.session.cookie.name"));
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
	 * Gathers one kind of path across the contributions, in the order they were
	 * contributed and without a duplicate.
	 */
	private static List<String> collect(List<SecuredPathsContribution> contributions,
			Function<SecuredPathsContribution, List<String>> kind) {
		return contributions.stream()
			.flatMap((contribution) -> kind.apply(contribution).stream())
			.distinct()
			.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * Appends the paths that are not already there, so a path declared twice is matched
	 * once.
	 */
	private static void add(List<String> paths, List<String> added) {
		added.stream().filter((path) -> !paths.contains(path)).forEach(paths::add);
	}

	/**
	 * Whether the paths that change the gateway are put behind an authenticated
	 * principal.
	 * <p>
	 * Under {@link GatewayUiSecurityProperties.WriteMode#AUTO}, the answer is yes as soon
	 * as the chain has something to authenticate against. Closing them with nothing
	 * behind the door would leave no way through it at all, which is why the default
	 * stops there and says so rather than locking an application out of its own route
	 * management.
	 */
	private static boolean restrictWrite(GatewayUiSecurityProperties properties, boolean canAuthenticate) {
		return switch (properties.getWriteMode()) {
			case AUTHENTICATED -> true;
			case PERMIT_ALL -> false;
			case AUTO -> canAuthenticate;
		};
	}

	/**
	 * Says that the sessions the console is about to start live in the memory of this
	 * instance alone.
	 * <p>
	 * A second instance is then a second console: the {@code SESSION} cookie means
	 * nothing to whichever one did not issue it, so every request a load balancer sends
	 * elsewhere finds no principal and goes back to the login page. Signing in through a
	 * provider fails harder than that &mdash; the authorization request, its
	 * {@code state} and the PKCE verifier are held in the same session, so a callback
	 * landing on another instance is rejected outright.
	 * <p>
	 * Only the deployment knows how many instances there are, so this says what is true
	 * of this one and leaves the conclusion to whoever reads it: a single-instance
	 * gateway has nothing to do about it.
	 */
	private static void warnInMemorySessions(ObjectProvider<WebSessionManager> sessionManager) {
		if (sessionManager.getIfAvailable() instanceof DefaultWebSessionManager manager
				&& manager.getSessionStore() instanceof InMemoryWebSessionStore) {
			LOG.warn("The console is behind a login page and this instance keeps the sessions it opens in its own "
					+ "memory. Running more than one instance behind a load balancer then loses the session on "
					+ "every request served by another one, and an OpenID Connect callback reaching another one is "
					+ "rejected. Add a shared session store: the spring-boot-starter-session-data-redis "
					+ "starter, not the spring-session-data-redis artifact on its own, which carries no "
					+ "auto-configuration. A single gateway instance needs nothing.");
		}
	}

	/**
	 * Says which paths are left open, and how to close them. An operator reading the logs
	 * of a gateway has no other way of learning that its route management answers to
	 * anyone.
	 */
	private static void warnOpenWritePaths(GatewayUiSecurityProperties properties, List<String> write) {
		if (write.isEmpty() || properties.getWriteMode() == GatewayUiSecurityProperties.WriteMode.PERMIT_ALL) {
			return;
		}
		LOG.warn(
				"The gateway endpoints that change its configuration are reachable without authentication: {}. "
						+ "The console is open and no user directory was found to authenticate against. Configure "
						+ "{}.user.password, declare a ReactiveUserDetailsService, or set {}.mode=authenticated.",
				write, SECURITY_PREFIX, SECURITY_PREFIX);
	}

	/**
	 * Leaves the declared paths out of the CSRF protection, without touching the rest:
	 * the default matcher still decides on the method, so the safe ones stay out of it
	 * and every other path keeps the protection the console gives it.
	 */
	private static void exemptFromCsrf(ServerHttpSecurity http, List<String> csrfExempt) {
		if (csrfExempt.isEmpty()) {
			return;
		}
		http.csrf((csrf) -> csrf.requireCsrfProtectionMatcher(
				new AndServerWebExchangeMatcher(CsrfWebFilter.DEFAULT_CSRF_MATCHER, new NegatedServerWebExchangeMatcher(
						ServerWebExchangeMatchers.pathMatchers(csrfExempt.toArray(String[]::new))))));
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
	 * offers it under. Both back off when no client is registered: the module being on
	 * the classpath does not mean a provider was configured.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ReactiveClientRegistrationRepository.class)
	@ConditionalOnProperty(prefix = SECURITY_PREFIX, name = "mode", havingValue = "authenticated")
	static class OAuth2LoginConfiguration {

		/**
		 * Prefix the console declares the identity providers of its login page under,
		 * spelling out the Spring Security keys it mirrors.
		 */
		private static final String CLIENT_PREFIX = SECURITY_PREFIX + ".spring.security.oauth2.client";

		/**
		 * Contributes the OpenID Connect login to the chain of the console.
		 * @param registrations the client registrations of the application, if it has any
		 * @param consoleRegistrations the ones the console declared for itself, if the
		 * properties module is on the classpath
		 * @return the contribution, which does nothing when no client is registered
		 */
		@Bean
		UiSecurityCustomizer gatewayUiOAuth2LoginCustomizer(
				ObjectProvider<ReactiveClientRegistrationRepository> registrations,
				ObjectProvider<UiLoginRegistrations> consoleRegistrations) {
			return new UiSecurityCustomizer() {

				@Override
				public void customize(ServerHttpSecurity http) {
					ReactiveClientRegistrationRepository repository = repository(consoleRegistrations, registrations);
					if (repository == null) {
						return;
					}
					http.oauth2Login((oauth2) -> {
						// Named rather than left to the bean of the application, which is
						// the one the console narrowed or replaced.
						oauth2.clientRegistrationRepository(repository);
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
					return (repository(consoleRegistrations, registrations) != null)
							? List.of("/oauth2/authorization/**", "/login/oauth2/code/**") : List.of();
				}

			};
		}

		/**
		 * The registrations the console signs in through.
		 * <p>
		 * {@link UiLoginRegistrations} is the answer whenever it was resolved, including
		 * when it resolved to nothing: having narrowed the registrations down to none is
		 * a decision, and falling back on those of the application would hand back the
		 * very list the narrowing was there to leave out. The application is only asked
		 * when the bean is absent altogether, which is the classpath holding the Spring
		 * Security client without the Spring Boot module reading its properties.
		 */
		private static ReactiveClientRegistrationRepository repository(
				ObjectProvider<UiLoginRegistrations> consoleRegistrations,
				ObjectProvider<ReactiveClientRegistrationRepository> registrations) {
			UiLoginRegistrations console = consoleRegistrations.getIfAvailable();
			return (console != null) ? console.repository() : registrations.getIfAvailable();
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
		 * @param consoleRegistrations the ones the console signs in through, if the
		 * properties module is on the classpath
		 * @return the providers the login page offers a button for
		 */
		@Bean
		@ConditionalOnMissingBean
		UiLoginProviders gatewayUiLoginProviders(ObjectProvider<ReactiveClientRegistrationRepository> registrations,
				ObjectProvider<UiLoginRegistrations> consoleRegistrations) {
			Map<String, String> providers = new LinkedHashMap<>();
			if (repository(consoleRegistrations, registrations) instanceof Iterable<?> iterable) {
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

		/**
		 * Decides, once at start-up, which client registrations the console signs in
		 * through.
		 * <p>
		 * Left alone, those are the ones the application registered, which is right on a
		 * gateway whose console answers to the provider its traffic answers to. Where it
		 * is not, {@code spring.security.oauth2.client} holds the technical clients the
		 * routes relay tokens with, and the login page would offer a button per one of
		 * them. Three ways out, from the narrowest:
		 * <ul>
		 * <li>a registration that is not an authorization code client is never offered
		 * &mdash; a button starting a grant no browser can complete cannot work, so it is
		 * dropped whatever the configuration says;</li>
		 * <li>{@code ...client.use} names the registration ids the console keeps out of
		 * the ones the application declared;</li>
		 * <li>{@code ...client.registration} and {@code ...client.provider} declare
		 * registrations of the console's own, read exactly as the Spring Security keys
		 * they spell out, and replace the ones of the application altogether.</li>
		 * </ul>
		 * <p>
		 * The whole configuration is bound here rather than through
		 * {@code @ConfigurationProperties}: a second {@link OAuth2ClientProperties} bean
		 * would make the one Spring Boot injects into its own client auto-configuration
		 * ambiguous, and the gateway would fail to start.
		 */
		@Configuration(proxyBeanMethods = false)
		@ConditionalOnClass(OAuth2ClientProperties.class)
		static class ConsoleRegistrationsConfiguration {

			/**
			 * Resolves the registrations the console offers on its login page.
			 * @param environment the environment the configuration of the console is read
			 * from
			 * @param registrations the client registrations of the application, if it has
			 * any
			 * @return the registrations the console signs in through
			 */
			@Bean
			@ConditionalOnMissingBean
			UiLoginRegistrations gatewayUiLoginRegistrations(Environment environment,
					ObjectProvider<ReactiveClientRegistrationRepository> registrations) {
				Binder binder = Binder.get(environment);
				OAuth2ClientProperties declared = binder.bind(CLIENT_PREFIX, OAuth2ClientProperties.class)
					.orElseGet(OAuth2ClientProperties::new);
				declared.validate();
				List<String> use = binder.bind(CLIENT_PREFIX + ".use", Bindable.listOf(String.class)).orElse(List.of());
				Map<String, ClientRegistration> own = new OAuth2ClientPropertiesMapper(declared)
					.asClientRegistrations();
				if (!own.isEmpty()) {
					return new UiLoginRegistrations(offered(own.values(), use));
				}
				ReactiveClientRegistrationRepository application = registrations.getIfAvailable();
				// A repository resolving issuers dynamically cannot be enumerated, so
				// there is nothing to narrow and no button to offer either.
				return new UiLoginRegistrations((application instanceof Iterable<?> iterable)
						? offered(clientRegistrations(iterable), use) : application);
			}

			/**
			 * Keeps the registrations the console may actually sign in through, and holds
			 * them in a repository of its own. None left, there is no provider to offer
			 * and the login page falls back on the credentials form alone.
			 */
			private static ReactiveClientRegistrationRepository offered(Collection<ClientRegistration> candidates,
					List<String> use) {
				List<ClientRegistration> offered = candidates.stream()
					.filter((registration) -> AuthorizationGrantType.AUTHORIZATION_CODE
						.equals(registration.getAuthorizationGrantType()))
					.filter((registration) -> use.isEmpty() || use.contains(registration.getRegistrationId()))
					.toList();
				if (offered.isEmpty()) {
					if (!candidates.isEmpty()) {
						LOG.warn("None of the {} client registration(s) found can sign into the console: an "
								+ "authorization code client is needed, and {}. The login page offers no provider.",
								candidates.size(), use.isEmpty() ? "none of them is one"
										: "%s.use narrows them to %s".formatted(CLIENT_PREFIX, use));
					}
					return null;
				}
				return new InMemoryReactiveClientRegistrationRepository(offered);
			}

			/**
			 * Reads the registrations out of a repository holding them in memory. The
			 * element type is lost with the {@code instanceof} that got us here &mdash;
			 * {@code ReactiveClientRegistrationRepository} is not itself an
			 * {@code Iterable}, so what it holds can only be checked one item at a time.
			 */
			private static List<ClientRegistration> clientRegistrations(Iterable<?> repository) {
				List<ClientRegistration> registrations = new ArrayList<>();
				for (Object candidate : repository) {
					if (candidate instanceof ClientRegistration registration) {
						registrations.add(registration);
					}
				}
				return registrations;
			}

		}

	}

	/**
	 * Contributes the resource server to the chain, so the endpoints of the console
	 * answer a Bearer token as well as a session. It backs off when the application
	 * configured no issuer, since there would be no decoder to validate the token with.
	 * <p>
	 * Unlike the login contributions, this one is declared whatever the mode: an open
	 * console still has endpoints that change the gateway, and on a gateway whose
	 * authentication is an authorization server rather than a user directory, a Bearer
	 * token is the only way to reach them.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ReactiveJwtDecoder.class)
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
			return new UiSecurityCustomizer() {

				@Override
				public void customize(ServerHttpSecurity http) {
					ReactiveJwtDecoder consoleDecoder = decoder(properties, decoder);
					if (consoleDecoder == null) {
						return;
					}
					http.oauth2ResourceServer((oauth2) -> oauth2.jwt((jwt) -> {
						jwt.jwtDecoder(consoleDecoder);
						String claim = properties.getRolesClaim();
						if (StringUtils.hasText(claim)) {
							jwt.jwtAuthenticationConverter(rolesConverter(claim));
						}
					}));
				}

				@Override
				public boolean appliesWhenOpen() {
					return decoder(properties, decoder) != null;
				}

			};
		}

		/**
		 * The decoder the console validates its Bearer tokens with: its own when an
		 * issuer was named for it, and the one the application declared otherwise.
		 * <p>
		 * The two are worth separating. Without an issuer of its own the console inherits
		 * the decoder built for the traffic the gateway routes, which is right when both
		 * answer to the same authorization server and wrong when they do not &mdash; and
		 * {@code spring.security.oauth2.resourceserver} holds one issuer for the whole
		 * application, so there is no way to say both there.
		 * <p>
		 * The issuer is asked for its keys on the first token that arrives rather than at
		 * start-up, so a gateway comes up whether or not the provider is answering.
		 */
		private static ReactiveJwtDecoder decoder(GatewayUiSecurityProperties properties,
				ObjectProvider<ReactiveJwtDecoder> applicationDecoder) {
			String issuer = properties.getOauth2().getResourceserver().getJwt().getIssuerUri();
			if (StringUtils.hasText(issuer)) {
				return new SupplierReactiveJwtDecoder(() -> ReactiveJwtDecoders.fromIssuerLocation(issuer));
			}
			return applicationDecoder.getIfAvailable();
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
