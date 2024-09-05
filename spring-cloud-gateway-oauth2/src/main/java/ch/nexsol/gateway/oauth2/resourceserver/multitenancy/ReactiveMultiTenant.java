/*
 * Copyright 2024 the original author or authors.
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

import reactor.core.publisher.Mono;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.util.Assert;

/**
 * Class to build an JwtIssuerReactiveAuthenticationManagerResolver to manage
 * multi-tenancy
 */
public class ReactiveMultiTenant {

	private ReactiveMultiTenant() {
	}

	private JwtIssuerReactiveAuthenticationManagerResolver authenticationManagerResolver;

	public static ReactiveMultiTenantBuilder builder() {
		return new ReactiveMultiTenantBuilder();
	}

	/**
	 * @return the authenticationManagerResolver
	 */
	public JwtIssuerReactiveAuthenticationManagerResolver getAuthenticationManagerResolver() {
		return this.authenticationManagerResolver;
	}

	public static class ReactiveMultiTenantBuilder {

		private ResourceServerMultiTenantProperties resourceServerMultiTenantProperties;

		private Converter<Jwt, Mono<AbstractAuthenticationToken>> converter;

		public ReactiveMultiTenantBuilder resourceServerMultiTenantProperties(
				ResourceServerMultiTenantProperties resourceServerMultiTenantProperties) {
			this.resourceServerMultiTenantProperties = resourceServerMultiTenantProperties;
			return this;
		}

		public ReactiveMultiTenantBuilder converter(Converter<Jwt, Mono<AbstractAuthenticationToken>> converter) {
			this.converter = converter;
			return this;
		}

		public ReactiveMultiTenant build() {
			// needs check validation
			Assert.notNull(this.resourceServerMultiTenantProperties,
					"resourceserver multitenant properties cannot be null");
			Assert.notNull(this.resourceServerMultiTenantProperties.getIssuerUri(),
					"resourceserver multitenant list cannot be null");
			ReactiveMultiTenant reactiveMultiTenant = new ReactiveMultiTenant();
			reactiveMultiTenant.authenticationManagerResolver = new JwtIssuerReactiveAuthenticationManagerResolver(
					new TrustedIssuerJwtReactiveAuthenticationManagerResolver(
							this.resourceServerMultiTenantProperties.getIssuerUri()::contains, this.converter));
			return reactiveMultiTenant;
		}

	}

}
