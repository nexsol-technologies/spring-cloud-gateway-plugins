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

package ch.nexsol.gateway.oauth2.autoconfigure;

import ch.nexsol.gateway.oauth2.resourceserver.ResourceServerPluginsProperties;
import ch.nexsol.gateway.oauth2.resourceserver.authorities.ConfigurableJwtGrantedAuthoritiesConverter;
import ch.nexsol.gateway.oauth2.resourceserver.authorities.DefaultJwtGrantedAuthoritiesConverter;
import ch.nexsol.gateway.oauth2.resourceserver.authorities.condition.ConfigurableJwtGrantedAuthoritiesConfiguredCondition;
import ch.nexsol.gateway.oauth2.resourceserver.authorities.condition.OnMissingConfigurableJwtGrantedAuthoritiesConfiguredCondition;
import ch.nexsol.gateway.oauth2.resourceserver.multitenancy.TrustedIssuerJwtReactiveAuthenticationManagerResolver;
import ch.nexsol.gateway.oauth2.resourceserver.multitenancy.condition.MultitenancyConfiguredCondition;
import ch.nexsol.gateway.oauth2.resourceserver.multitenancy.condition.OnMissingMultitenancyConfiguredCondition;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.autoconfigure.security.oauth2.resource.IssuerUriCondition;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.ReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerReactiveAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.ServerWebExchange;

@AutoConfiguration
public class ResourceServerAutoConfiguration {

	@Bean
	@Conditional(ConfigurableJwtGrantedAuthoritiesConfiguredCondition.class)
	Converter<Jwt, AbstractAuthenticationToken> configurableJwtGrantedAuthoritiesConverter(
			ResourceServerPluginsProperties resourceServerPluginsProperties) {
		return new ConfigurableJwtGrantedAuthoritiesConverter(
				resourceServerPluginsProperties.getGrantedAuthoritiesMapping().getJsonPath());
	}

	@Bean
	@Conditional(OnMissingConfigurableJwtGrantedAuthoritiesConfiguredCondition.class)
	Converter<Jwt, AbstractAuthenticationToken> defaultJwtGrantedAuthoritiesConverter(
			@Value("${spring.cloud.gateway.resourcename:${spring.application.name}}") String resourceName) {
		return new DefaultJwtGrantedAuthoritiesConverter(resourceName);
	}

	@Bean
	@ConfigurationProperties("spring.security.oauth2.resourceserver")
	ResourceServerPluginsProperties resourceServerPluginsProperties() {
		return new ResourceServerPluginsProperties();
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnWebApplication(type = Type.REACTIVE)
	@ConditionalOnClass({ WebFluxConfigurer.class })
	public static class ReactiveOAuth2MultitenancyConfiguration {

		@Bean
		@Conditional(MultitenancyConfiguredCondition.class)
		ReactiveAuthenticationManagerResolver<ServerWebExchange> jwtIssuerReactiveAuthenticationManagerResolver(
				ResourceServerPluginsProperties resourceServerMultiTenantProperties,
				DefaultJwtGrantedAuthoritiesConverter converter) {

			return new JwtIssuerReactiveAuthenticationManagerResolver(
					new TrustedIssuerJwtReactiveAuthenticationManagerResolver(
							resourceServerMultiTenantProperties.getIssuerUri()::contains,
							new ReactiveJwtAuthenticationConverterAdapter(converter)));

		}

		@Bean
		@Conditional(DefaultResourceserverConfigurationCondition.class)
		ReactiveAuthenticationManagerResolver<ServerWebExchange> defaultJwtIssuerReactiveAuthenticationManagerResolver(
				OAuth2ResourceServerProperties properties, Converter<Jwt, AbstractAuthenticationToken> converter) {

			return new JwtIssuerReactiveAuthenticationManagerResolver(
					new TrustedIssuerJwtReactiveAuthenticationManagerResolver(
							properties.getJwt().getIssuerUri()::equals,
							new ReactiveJwtAuthenticationConverterAdapter(converter)));
		}

	}

	static class DefaultResourceserverConfigurationCondition extends AllNestedConditions {

		DefaultResourceserverConfigurationCondition() {
			super(ConfigurationPhase.REGISTER_BEAN);
		}

		@Conditional(IssuerUriCondition.class)
		static class DefaultConfig {

		}

		@Conditional(OnMissingMultitenancyConfiguredCondition.class)
		static class NoMultiTenancy {

		}

	}

}
