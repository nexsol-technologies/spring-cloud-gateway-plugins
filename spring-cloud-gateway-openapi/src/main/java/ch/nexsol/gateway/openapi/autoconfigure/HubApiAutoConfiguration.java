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

package ch.nexsol.gateway.openapi.autoconfigure;

import java.net.URI;
import java.util.Set;

import ch.nexsol.gateway.openapi.hub.OpenapiService;
import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import ch.nexsol.gateway.openapi.hub.filter.OpenapiModifyResponseBodyGatewayFilterFactory;
import org.springdoc.core.properties.SwaggerUiConfigParameters;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.config.conditional.ConditionalOnEnabledFilter;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.ServerCodecConfigurer;

@AutoConfiguration
public class HubApiAutoConfiguration {

	@Bean
	@ConditionalOnClass(ReactiveDiscoveryClient.class)
	OpenapiService openapiService(ReactiveDiscoveryClient discoveryClient) {
		return new OpenapiService(discoveryClient);
	}

	@Bean
	@ConditionalOnClass(ReactiveDiscoveryClient.class)
	HubDiscoveryRouteLocator hubDiscoveryRouteLocator(ReactiveDiscoveryClient discoveryClient,
			DiscoveryLocatorProperties properties, OpenapiService openapiService,
			SwaggerUiConfigParameters swaggerUiConfigParameters) {
		return new HubDiscoveryRouteLocator(discoveryClient, properties, openapiService, swaggerUiConfigParameters);
	}

	@Bean
	@ConditionalOnEnabledFilter
	OpenapiModifyResponseBodyGatewayFilterFactory customModifyResponseBodyGatewayFilterFactory(
			ServerCodecConfigurer codecConfigurer, Set<MessageBodyDecoder> bodyDecoders,
			Set<MessageBodyEncoder> bodyEncoders, @Value("${openapi.gateway.uri}") URI apiGatewayUri) {
		return new OpenapiModifyResponseBodyGatewayFilterFactory(codecConfigurer.getReaders(), bodyDecoders,
				bodyEncoders, apiGatewayUri);
	}

}
