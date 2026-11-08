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

import java.util.List;
import java.util.Optional;

import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierReactiveJwtDecoder;
import org.springframework.util.StringUtils;

/**
 * The decoder validating the Bearer tokens a plugin accepts: the issuer it is given, else
 * the decoder the application declared.
 */
public final class IssuerJwtDecoders {

	private IssuerJwtDecoders() {
	}

	/**
	 * Resolves the decoder. The issuer is asked for its keys on the first token that
	 * arrives rather than at start-up, so a gateway comes up whether or not the provider
	 * is answering. The audiences apply to that issuer alone: a decoder the application
	 * declared already carries the audiences the application gave it.
	 * @param jwt the issuer and the audiences of the plugin
	 * @param applicationDecoder the decoder the application declared, or {@code null}
	 * @return the decoder, empty with neither an issuer nor an application decoder
	 */
	public static Optional<ReactiveJwtDecoder> decoder(PluginSecurityProperties.Jwt jwt,
			ReactiveJwtDecoder applicationDecoder) {
		return decoder(jwt.getIssuerUri(), jwt.getAudiences(), applicationDecoder);
	}

	/**
	 * Resolves the decoder, as
	 * {@link #decoder(PluginSecurityProperties.Jwt, ReactiveJwtDecoder)} does, from the
	 * issuer and the audiences themselves.
	 * @param issuerUri the issuer, or {@code null}
	 * @param audiences the audiences a token must carry one of, empty to accept any
	 * @param applicationDecoder the decoder the application declared, or {@code null}
	 * @return the decoder, empty with neither an issuer nor an application decoder
	 */
	public static Optional<ReactiveJwtDecoder> decoder(String issuerUri, List<String> audiences,
			ReactiveJwtDecoder applicationDecoder) {
		if (!StringUtils.hasText(issuerUri)) {
			return Optional.ofNullable(applicationDecoder);
		}
		ReactiveJwtDecoder decoder = new SupplierReactiveJwtDecoder(
				() -> ReactiveJwtDecoders.fromIssuerLocation(issuerUri));
		if (audiences.isEmpty()) {
			return Optional.of(decoder);
		}
		return Optional.of(new AudienceCheckingJwtDecoder(decoder, audiences));
	}

}
