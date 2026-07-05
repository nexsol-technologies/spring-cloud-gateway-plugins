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

package ch.nexsol.gateway.openapi.autoconfigure;

import java.net.URI;
import java.util.Set;

import ch.nexsol.gateway.openapi.hub.OpenapiService;
import ch.nexsol.gateway.openapi.hub.SpringDocOpenapiRoutes;
import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import ch.nexsol.gateway.openapi.hub.filter.OpenapiModifyResponseBodyGatewayFilterFactory;
import org.springdoc.core.properties.SwaggerUiConfigProperties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.gateway.config.conditional.ConditionalOnEnabledFilter;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.ServerCodecConfigurer;

/**
 * Auto-configuration that wires up the OpenAPI hub, aggregating the OpenAPI documentation
 * of the services discovered by the gateway. It is only active when the
 * {@code spring.cloud.gateway.openapi.hub.enabled} property is set to {@code true}.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "spring.cloud.gateway.openapi.hub.enabled", havingValue = "true", matchIfMissing = false)
public class HubApiAutoConfiguration {

	/**
	 * Registers the component that publishes the discovered OpenAPI routes as Swagger UI
	 * URLs.
	 * @param routeLocator the gateway route locator
	 * @param swaggerUiConfigProperties the SpringDoc Swagger UI configuration to populate
	 * @return the {@link SpringDocOpenapiRoutes} bean
	 */
	@Bean
	SpringDocOpenapiRoutes springDocOpenapiRoutes(RouteLocator routeLocator,
			SwaggerUiConfigProperties swaggerUiConfigProperties) {
		return new SpringDocOpenapiRoutes(routeLocator, swaggerUiConfigProperties);
	}

	/**
	 * Registers the service that resolves the OpenAPI documentation endpoint of each
	 * discovered service instance.
	 * @param discoveryClient the reactive discovery client
	 * @return the {@link OpenapiService} bean
	 */
	@Bean
	@ConditionalOnClass(ReactiveDiscoveryClient.class)
	OpenapiService openapiService(ReactiveDiscoveryClient discoveryClient) {
		return new OpenapiService(discoveryClient);
	}

	/**
	 * Registers the route locator that creates OpenAPI documentation routes for the
	 * discovered services.
	 * @param discoveryClient the reactive discovery client
	 * @param properties the discovery locator properties
	 * @param openapiService the service used to discover OpenAPI endpoints
	 * @return the {@link HubDiscoveryRouteLocator} bean
	 */
	@Bean
	@ConditionalOnClass(ReactiveDiscoveryClient.class)
	HubDiscoveryRouteLocator hubDiscoveryRouteLocator(ReactiveDiscoveryClient discoveryClient,
			DiscoveryLocatorProperties properties, OpenapiService openapiService) {
		return new HubDiscoveryRouteLocator(discoveryClient, properties, openapiService);
	}

	/**
	 * Registers the filter factory that rewrites the {@code servers} section of the
	 * proxied OpenAPI documents so they point at the gateway.
	 * @param codecConfigurer the server codec configurer providing the message readers
	 * @param bodyDecoders the available message body decoders
	 * @param bodyEncoders the available message body encoders
	 * @param apiGatewayUri the public gateway URI to advertise in the OpenAPI servers
	 * @return the {@link OpenapiModifyResponseBodyGatewayFilterFactory} bean
	 */
	@Bean
	@ConditionalOnEnabledFilter
	OpenapiModifyResponseBodyGatewayFilterFactory customModifyResponseBodyGatewayFilterFactory(
			ServerCodecConfigurer codecConfigurer, Set<MessageBodyDecoder> bodyDecoders,
			Set<MessageBodyEncoder> bodyEncoders, @Value("${openapi.gateway.uri}") URI apiGatewayUri) {
		return new OpenapiModifyResponseBodyGatewayFilterFactory(codecConfigurer.getReaders(), bodyDecoders,
				bodyEncoders, apiGatewayUri);
	}

}
