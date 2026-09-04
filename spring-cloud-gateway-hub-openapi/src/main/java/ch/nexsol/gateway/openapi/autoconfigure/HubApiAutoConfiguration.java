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
import java.util.List;
import java.util.Map;
import java.util.Set;

import ch.nexsol.gateway.audit.AuditProperties;
import ch.nexsol.gateway.commons.CodecLimits;
import ch.nexsol.gateway.openapi.HubOpenapiProperties;
import ch.nexsol.gateway.openapi.hub.AuditExclusionBeanPostProcessor;
import ch.nexsol.gateway.openapi.hub.GatewayIssuers;
import ch.nexsol.gateway.openapi.hub.OpenapiService;
import ch.nexsol.gateway.openapi.hub.SpringDocOpenapiRoutes;
import ch.nexsol.gateway.openapi.hub.StaticOpenapiDocsRouteLocator;
import ch.nexsol.gateway.openapi.hub.discovery.HubDiscoveryRouteLocator;
import ch.nexsol.gateway.openapi.hub.filter.OpenapiModifyResponseBodyGatewayFilterFactory;
import ch.nexsol.gateway.routes.openapi.OpenapiSourcesLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.ReactiveDiscoveryClient;
import org.springframework.cloud.client.discovery.composite.reactive.ReactiveCompositeDiscoveryClientAutoConfiguration;
import org.springframework.cloud.gateway.config.conditional.ConditionalOnEnabledFilter;
import org.springframework.cloud.gateway.discovery.DiscoveryLocatorProperties;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyDecoder;
import org.springframework.cloud.gateway.filter.factory.rewrite.MessageBodyEncoder;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.util.unit.DataSize;

/**
 * Auto-configuration that wires up the OpenAPI hub, aggregating the OpenAPI documentation
 * of the services discovered by the gateway. It is only active when the
 * {@code spring.cloud.gateway.server.webflux.hub-openapi.enabled} property is set to
 * {@code true}.
 */
@AutoConfiguration(after = ReactiveCompositeDiscoveryClientAutoConfiguration.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.hub-openapi.enabled", havingValue = "true",
		matchIfMissing = false)
@EnableConfigurationProperties(HubOpenapiProperties.class)
public class HubApiAutoConfiguration {

	private static final Logger LOG = LoggerFactory.getLogger(HubApiAutoConfiguration.class);

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
	 * discovered service instance, only when the application actually has a discovery
	 * client: without one the hub keeps serving the statically configured contracts.
	 * @param discoveryClient the reactive discovery client
	 * @param hubProperties the hub configuration, bounding the probes
	 * @return the {@link OpenapiService} bean
	 */
	@Bean
	@ConditionalOnBean(ReactiveDiscoveryClient.class)
	OpenapiService openapiService(ReactiveDiscoveryClient discoveryClient, HubOpenapiProperties hubProperties) {
		return new OpenapiService(discoveryClient, hubProperties.getDiscovery());
	}

	/**
	 * Registers the route locator that creates OpenAPI documentation routes for the
	 * discovered services, only when the application actually has a discovery client.
	 * @param discoveryClient the reactive discovery client
	 * @param properties the discovery locator properties
	 * @param openapiService the service used to discover OpenAPI endpoints
	 * @param hubProperties the hub configuration, bounding the probes
	 * @return the {@link HubDiscoveryRouteLocator} bean
	 */
	@Bean
	@ConditionalOnBean(ReactiveDiscoveryClient.class)
	HubDiscoveryRouteLocator hubDiscoveryRouteLocator(ReactiveDiscoveryClient discoveryClient,
			DiscoveryLocatorProperties properties, OpenapiService openapiService, HubOpenapiProperties hubProperties) {
		return new HubDiscoveryRouteLocator(discoveryClient, properties, openapiService, hubProperties.getDiscovery());
	}

	/**
	 * Registers the filter factory that rewrites the {@code servers} section of the
	 * proxied OpenAPI documents so they point at the gateway.
	 * @param bodyDecoders the available message body decoders
	 * @param bodyEncoders the available message body encoders
	 * @param apiGatewayUri the public gateway URI to advertise in the OpenAPI servers
	 * @param hubProperties the hub configuration, naming where the advertised OpenID
	 * Connect issuer comes from and how large a document may be
	 * @param environment the environment the gateway issuers are read from
	 * @return the {@link OpenapiModifyResponseBodyGatewayFilterFactory} bean
	 */
	@Bean
	@ConditionalOnEnabledFilter
	OpenapiModifyResponseBodyGatewayFilterFactory customModifyResponseBodyGatewayFilterFactory(
			Set<MessageBodyDecoder> bodyDecoders, Set<MessageBodyEncoder> bodyEncoders,
			@Value("${spring.cloud.gateway.server.webflux.hub-openapi.gateway-uri}") URI apiGatewayUri,
			HubOpenapiProperties hubProperties, Environment environment) {
		return new OpenapiModifyResponseBodyGatewayFilterFactory(documentReaders(hubProperties.getMaxDocumentSize()),
				bodyDecoders, bodyEncoders, apiGatewayUri, advertisedIssuers(hubProperties, environment));
	}

	/**
	 * The readers the rewriting decodes a document with. They are the hub's own, not the
	 * ones of the {@link ServerCodecConfigurer} bean: that bean is what the gateway reads
	 * every routed request with, and raising its ceiling to fit an OpenAPI document
	 * raises it for all of them. Nothing is lost by not sharing it &mdash; the filter
	 * decodes to {@code byte[]}, which every default codec set can do.
	 * @param maxDocumentSize the largest document the rewriting may buffer
	 * @return the message readers, bounded by that size
	 */
	private static List<HttpMessageReader<?>> documentReaders(DataSize maxDocumentSize) {
		ServerCodecConfigurer configurer = ServerCodecConfigurer.create();
		configurer.defaultCodecs().maxInMemorySize(CodecLimits.maxInMemoryBytes(maxDocumentSize));
		return configurer.getReaders();
	}

	/**
	 * The issuers the aggregated documents advertise: those of the gateway when it was
	 * asked for them, and none &mdash; leaving every document as its service wrote it
	 * &mdash; otherwise.
	 * <p>
	 * Asking for the issuers of a gateway that has none configured is a contradiction
	 * worth a word: the request cannot be honoured, and the documents keep pointing at
	 * whatever internal address their services declared.
	 */
	private static Map<String, String> advertisedIssuers(HubOpenapiProperties properties, Environment environment) {
		if (properties.getSecurity().getIssuer() != HubOpenapiProperties.Security.Issuer.GATEWAY) {
			return Map.of();
		}
		Map<String, String> issuers = GatewayIssuers.from(environment);
		if (issuers.isEmpty()) {
			LOG.warn("hub-openapi.security.issuer is GATEWAY, but this gateway is configured with no issuer: "
					+ "the aggregated documents keep the one their services declared. Configure "
					+ "spring.security.oauth2.resourceserver.multitenant or .jwt.issuer-uri.");
		}
		else {
			LOG.debug("Aggregated OpenAPI documents will advertise the gateway issuers {}", issuers.keySet());
		}
		return issuers;
	}

	/**
	 * Optional integration active only when the auditing plugin is on the classpath:
	 * keeps the documentation endpoints out of the audit trail, which a console polling
	 * the contracts would otherwise flood.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(AuditProperties.class)
	static class AuditHubIntegrationConfiguration {

		/**
		 * Excludes the paths the hub serves from the global auditing web filter. Declared
		 * {@code static} because a bean post-processor must not force the enclosing
		 * configuration to be created early.
		 * @param springDocProperties the SpringDoc configuration, when it is on
		 * @param swaggerUiProperties the Swagger UI configuration, when it is on
		 * @return the post-processor excluding the documentation endpoints
		 */
		@Bean
		static BeanPostProcessor hubOpenapiAuditExclusionBeanPostProcessor(
				ObjectProvider<SpringDocConfigProperties> springDocProperties,
				ObjectProvider<SwaggerUiConfigProperties> swaggerUiProperties) {
			return new AuditExclusionBeanPostProcessor(springDocProperties, swaggerUiProperties);
		}

	}

	/**
	 * Optional integration active only when the OpenAPI route generator is on the
	 * classpath: exposes its statically configured contracts in the aggregated Swagger
	 * UI.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(OpenapiSourcesLoader.class)
	static class RoutesOpenapiHubIntegrationConfiguration {

		/**
		 * Registers the locator emitting an OpenAPI documentation route for every
		 * configured OpenAPI source, so they appear in the aggregated Swagger UI. The
		 * sources are the resolved ones, so those declared in a document are included.
		 * @param sourcesLoader the resolver of the OpenAPI sources, when present
		 * @return the {@link StaticOpenapiDocsRouteLocator} bean
		 */
		@Bean
		StaticOpenapiDocsRouteLocator staticOpenapiDocsRouteLocator(
				ObjectProvider<OpenapiSourcesLoader> sourcesLoader) {
			return new StaticOpenapiDocsRouteLocator(sourcesLoader);
		}

	}

}
