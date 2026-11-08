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

import java.util.Optional;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * The Bearer half of {@link PluginSecurityChain}, kept apart because it names the JWT
 * classes: {@link PluginSecurityChain#bearer} reaches it only once it has found them on
 * the classpath, so a plugin may leave the JOSE and resource server jars optional.
 */
final class PluginBearerSupport {

	private PluginBearerSupport() {
	}

	static boolean configure(ServerHttpSecurity http, PluginSecurityProperties.Jwt jwt, BeanFactory beanFactory) {
		Optional<ReactiveJwtDecoder> decoder = IssuerJwtDecoders.decoder(jwt,
				beanFactory.getBeanProvider(ReactiveJwtDecoder.class).getIfAvailable());
		decoder.ifPresent(
				(resolved) -> http.oauth2ResourceServer((oauth2) -> oauth2.jwt((spec) -> spec.jwtDecoder(resolved))));
		return decoder.isPresent();
	}

}
