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

package ch.nexsol.gateway.oauth2.resourceserver.multitenancy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;

/**
 * @author guerricmerle
 */
public class TrustedIssuerJwtReactiveAuthenticationManagerResolver
		implements ReactiveAuthenticationManagerResolver<String> {

	private final Map<String, Mono<ReactiveAuthenticationManager>> authenticationManagers = new ConcurrentHashMap<>();

	private final Predicate<String> trustedIssuer;

	private final Converter<Jwt, Mono<AbstractAuthenticationToken>> converter;

	public TrustedIssuerJwtReactiveAuthenticationManagerResolver(Predicate<String> trustedIssuer,
			Converter<Jwt, Mono<AbstractAuthenticationToken>> converter) {
		this.trustedIssuer = trustedIssuer;
		this.converter = converter;
	}

	@Override
	public Mono<ReactiveAuthenticationManager> resolve(String issuer) {
		if (!this.trustedIssuer.test(issuer)) {
			return Mono.empty();
		}
		return this.authenticationManagers.computeIfAbsent(issuer,
				(k) -> Mono.<ReactiveAuthenticationManager>fromCallable(() -> {
					JwtReactiveAuthenticationManager manager = new JwtReactiveAuthenticationManager(
							ReactiveJwtDecoders.fromIssuerLocation(k));
					if (this.converter != null) {
						manager.setJwtAuthenticationConverter(this.converter);
					}
					return manager;
				}).subscribeOn(Schedulers.boundedElastic()).cache());
	}

}
