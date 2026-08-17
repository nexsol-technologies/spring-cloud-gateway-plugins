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

package ch.nexsol.gateway.filter;

import java.security.Principal;
import java.util.Set;

import ch.nexsol.gateway.filter.IdentityPropagationProperties.Headers;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * Writes the identity behind a request onto the request the gateway forwards, as two sets
 * of headers: who is calling now, and who started the chain.
 * <p>
 * A service reaching another one through the gateway presents its own token, so the token
 * only ever names the last hop. The {@code origin} headers are what carries the identity
 * of the client the chain started from, all the way down &mdash; which is what a
 * downstream service needs to log, audit or authorise against the caller it never sees.
 * <p>
 * <strong>The origin identity is only believed when the caller is one of the configured
 * internal clients.</strong> These are headers: whoever calls the gateway from the
 * outside can send any of them, and a rule that preserved what was already there would
 * let an anonymous browser choose whose name the whole chain is recorded under. Every
 * header this filter owns is therefore removed from the incoming request, and written
 * again from the validated token &mdash; unless the caller is internal and carried an
 * origin of its own, which is the one case where the incoming value is the truth of a hop
 * the gateway already saw.
 * <p>
 * A request with no token has no identity to propagate: the headers are removed and
 * nothing is written back.
 */
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

	private final Set<String> internalClients;

	private final Headers current;

	private final Headers origin;

	/**
	 * Creates the filter.
	 * @param properties the header names and the clients whose origin is believed
	 */
	public IdentityPropagationFilter(IdentityPropagationProperties properties) {
		this.internalClients = Set.copyOf(properties.getInternalClients());
		this.current = properties.getCurrent();
		this.origin = properties.getOrigin();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Runs first so every other filter, and the request finally sent upstream, sees the
	 * headers this one settled.
	 */
	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		return exchange.getPrincipal()
			.mapNotNull(IdentityPropagationFilter::identityOf)
			.defaultIfEmpty(Identity.NONE)
			.flatMap((identity) -> chain.filter(propagate(exchange, identity)));
	}

	private ServerWebExchange propagate(ServerWebExchange exchange, Identity identity) {
		Identity chainOrigin = chainOrigin(exchange, identity);
		return exchange.mutate().request((request) -> request.headers((headers) -> {
			write(headers, this.current, identity);
			write(headers, this.origin, chainOrigin);
		})).build();
	}

	/**
	 * The identity the chain started from: the one the request carries when it comes from
	 * an internal client that was given it on an earlier hop, and the identity of the
	 * caller itself otherwise.
	 * <p>
	 * An internal client carrying no origin is the start of a chain of its own &mdash; a
	 * scheduled job calling another service is nobody's second hop &mdash; so it becomes
	 * the origin rather than losing one.
	 */
	private Identity chainOrigin(ServerWebExchange exchange, Identity identity) {
		if (!believesIncomingOrigin(identity)) {
			return identity;
		}
		Identity carried = incomingOrigin(exchange.getRequest().getHeaders());
		return carried.isEmpty() ? identity : carried;
	}

	/**
	 * Whether the origin the request carries is kept: only an internal client is trusted
	 * to have been given it by the gateway on an earlier hop.
	 * @param identity the identity of the caller, as validated from its token
	 * @return whether the incoming origin headers are believed
	 */
	private boolean believesIncomingOrigin(Identity identity) {
		return identity.client() != null && this.internalClients.contains(identity.client());
	}

	private Identity incomingOrigin(HttpHeaders headers) {
		return new Identity(headers.getFirst(this.origin.getIssuer()), headers.getFirst(this.origin.getClient()),
				headers.getFirst(this.origin.getUser()));
	}

	/**
	 * Writes an identity onto the three headers, removing those it has nothing to say
	 * about. Removing is what keeps a forged header from surviving the hop.
	 */
	private static void write(HttpHeaders headers, Headers names, Identity identity) {
		set(headers, names.getIssuer(), identity.issuer());
		set(headers, names.getClient(), identity.client());
		set(headers, names.getUser(), identity.user());
	}

	private static void set(HttpHeaders headers, String name, String value) {
		if (StringUtils.hasText(value)) {
			headers.set(name, value);
		}
		else {
			headers.remove(name);
		}
	}

	private static Identity identityOf(Principal principal) {
		Jwt jwt = extractJwt(principal);
		if (jwt == null) {
			return null;
		}
		String issuer = (jwt.getIssuer() != null) ? jwt.getIssuer().toString() : null;
		String client = firstWithText(jwt.getClaimAsString("azp"), jwt.getClaimAsString("client_id"));
		String user = firstWithText(jwt.getClaimAsString("preferred_username"), jwt.getSubject());
		return new Identity(issuer, client, user);
	}

	private static Jwt extractJwt(Principal principal) {
		if (principal instanceof AbstractOAuth2TokenAuthenticationToken<?> token
				&& token.getToken() instanceof Jwt jwt) {
			return jwt;
		}
		if (principal instanceof Authentication authentication && authentication.getPrincipal() instanceof Jwt jwt) {
			return jwt;
		}
		return null;
	}

	private static String firstWithText(String first, String second) {
		return StringUtils.hasText(first) ? first : second;
	}

	/**
	 * The three things a token says about who is calling.
	 *
	 * @param issuer the issuer of the token, {@code null} when unknown
	 * @param client the client the token was issued to, {@code null} when unknown
	 * @param user the user the token stands for, {@code null} when unknown
	 */
	private record Identity(String issuer, String client, String user) {

		/** No token, therefore nothing to propagate. */
		private static final Identity NONE = new Identity(null, null, null);

		/**
		 * @return whether the identity says nothing at all
		 */
		private boolean isEmpty() {
			return !StringUtils.hasText(this.issuer) && !StringUtils.hasText(this.client)
					&& !StringUtils.hasText(this.user);
		}

	}

}
