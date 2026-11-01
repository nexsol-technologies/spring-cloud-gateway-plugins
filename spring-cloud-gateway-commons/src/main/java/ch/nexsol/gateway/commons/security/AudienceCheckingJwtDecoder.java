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

import java.util.Collections;
import java.util.List;

import reactor.core.publisher.Mono;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Rejects the tokens of the issuer that were not issued for this gateway: a token the
 * issuer handed out for another resource is a valid token, and the issuer's signature
 * alone would let it through.
 */
public final class AudienceCheckingJwtDecoder implements ReactiveJwtDecoder {

	private static final OAuth2Error NOT_FOR_THIS_GATEWAY = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
			"The token was not issued for this gateway", null);

	private final ReactiveJwtDecoder delegate;

	private final List<String> audiences;

	/**
	 * Creates the decoder.
	 * @param delegate the decoder validating the signature and the issuer
	 * @param audiences the audiences a token must carry one of
	 */
	public AudienceCheckingJwtDecoder(ReactiveJwtDecoder delegate, List<String> audiences) {
		this.delegate = delegate;
		this.audiences = List.copyOf(audiences);
	}

	@Override
	public Mono<Jwt> decode(String token) {
		return this.delegate.decode(token).flatMap((jwt) -> {
			List<String> audience = jwt.getAudience();
			if (audience != null && !Collections.disjoint(audience, this.audiences)) {
				return Mono.just(jwt);
			}
			return Mono.error(
					new JwtValidationException(NOT_FOR_THIS_GATEWAY.getDescription(), List.of(NOT_FOR_THIS_GATEWAY)));
		});
	}

}
