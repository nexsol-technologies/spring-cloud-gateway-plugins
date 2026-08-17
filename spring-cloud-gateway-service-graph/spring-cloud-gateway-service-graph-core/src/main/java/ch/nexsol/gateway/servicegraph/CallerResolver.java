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

package ch.nexsol.gateway.servicegraph;

import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ch.nexsol.gateway.servicegraph.ServiceGraphProperties.Caller;
import reactor.core.publisher.Mono;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * Names the caller of a request, within a bounded set of names.
 * <p>
 * The name becomes a tag on a counter, so it cannot be free: a tag whose values are
 * unbounded creates one time series per value, in this instance's registry and in every
 * backend that scrapes it. The caller is therefore read from the claims naming the
 * client, never from the user or the address behind it, and the number of distinct names
 * is capped &mdash; everything past the cap is counted under {@link #OTHER}.
 */
public class CallerResolver {

	/** Reported when nothing named the caller. */
	public static final String UNKNOWN = "unknown";

	/** Reported for every caller past the configured maximum. */
	public static final String OTHER = "_other_";

	private final List<String> claims;

	private final String header;

	private final int max;

	private final boolean headerFirst;

	private final Set<String> known = ConcurrentHashMap.newKeySet();

	/**
	 * Creates the resolver.
	 * @param properties the caller side configuration
	 */
	public CallerResolver(Caller properties) {
		this.claims = List.copyOf(properties.getClaims());
		this.header = properties.getHeader();
		this.max = properties.getMax();
		this.headerFirst = properties.isHeaderFirst();
	}

	/**
	 * Name the caller of the given exchange: the first configured claim carrying a value,
	 * then the configured header, then {@link #UNKNOWN} &mdash; or the header first when
	 * the configuration says so.
	 * @param exchange the exchange to name the caller of
	 * @return a mono emitting the name, capped to the configured maximum
	 */
	public Mono<String> resolve(ServerWebExchange exchange) {
		Mono<String> fromHeader = Mono.fromSupplier(() -> fromHeader(exchange));
		Mono<String> fromClaims = exchange.getPrincipal().mapNotNull(this::fromPrincipal);
		Mono<String> resolved = this.headerFirst ? fromHeader.switchIfEmpty(fromClaims)
				: fromClaims.switchIfEmpty(fromHeader);
		return resolved.defaultIfEmpty(UNKNOWN).map(this::capped);
	}

	private String fromPrincipal(Principal principal) {
		Jwt jwt = extractJwt(principal);
		if (jwt == null) {
			return null;
		}
		for (String claim : this.claims) {
			String value = jwt.getClaimAsString(claim);
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
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

	private String fromHeader(ServerWebExchange exchange) {
		if (!StringUtils.hasText(this.header)) {
			return null;
		}
		String value = exchange.getRequest().getHeaders().getFirst(this.header);
		return StringUtils.hasText(value) ? value : null;
	}

	/**
	 * Keeps the name only while the graph has room for it.
	 * <p>
	 * The check and the insertion are not atomic, so a burst of new callers can name a
	 * handful more than the maximum. That is the intended trade: the guard exists to
	 * bound the number of series, not to be exact, and locking the data path of every
	 * request to make it exact would cost far more than the few extra names.
	 * @param caller the resolved name
	 * @return the name, or {@link #OTHER} once the maximum is reached
	 */
	private String capped(String caller) {
		if (this.known.contains(caller)) {
			return caller;
		}
		if (this.known.size() >= this.max) {
			return OTHER;
		}
		this.known.add(caller);
		return caller;
	}

}
