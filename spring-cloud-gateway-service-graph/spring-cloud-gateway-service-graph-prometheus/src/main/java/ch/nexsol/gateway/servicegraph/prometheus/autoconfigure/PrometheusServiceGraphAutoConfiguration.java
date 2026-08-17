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

package ch.nexsol.gateway.servicegraph.prometheus.autoconfigure;

import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import ch.nexsol.gateway.servicegraph.autoconfigure.ServiceGraphAutoConfiguration;
import ch.nexsol.gateway.servicegraph.prometheus.PrometheusServiceGraphProperties;
import ch.nexsol.gateway.servicegraph.prometheus.PrometheusServiceGraphSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Registers the Prometheus {@link ServiceGraphSource} when
 * {@code service-graph.provider=prometheus}. Ordered before the core auto-configuration
 * so its source wins over the local counters.
 */
@AutoConfiguration(before = ServiceGraphAutoConfiguration.class)
@ConditionalOnClass(WebClient.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.service-graph.enabled", matchIfMissing = true)
public class PrometheusServiceGraphAutoConfiguration {

	/**
	 * Holds the beans behind the provider selector. Two levels because
	 * {@code @ConditionalOnProperty} is not repeatable.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.service-graph.provider",
			havingValue = "prometheus")
	static class PrometheusSourceConfiguration {

		/**
		 * Binds the Prometheus service graph properties.
		 * @return the Prometheus service graph properties bean
		 */
		@Bean
		@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.service-graph.prometheus")
		PrometheusServiceGraphProperties prometheusServiceGraphProperties() {
			return new PrometheusServiceGraphProperties();
		}

		/**
		 * Registers the client used to query Prometheus, carrying the configured
		 * credentials.
		 * <p>
		 * Declared as its own bean so an application whose Prometheus needs more than
		 * Basic or a static bearer token &mdash; mTLS, OAuth2 client credentials, a
		 * service account token that rotates &mdash; can declare a
		 * {@code prometheusServiceGraphWebClient} bean of its own and have it used
		 * instead. The credentials are set on a client dedicated to Prometheus rather
		 * than through a {@code WebClientCustomizer}, which would put them on every
		 * client of the application.
		 * @param builder the application web client builder
		 * @param properties the Prometheus configuration
		 * @return the client querying Prometheus
		 */
		@Bean
		@ConditionalOnMissingBean(name = "prometheusServiceGraphWebClient")
		WebClient prometheusServiceGraphWebClient(WebClient.Builder builder,
				PrometheusServiceGraphProperties properties) {
			WebClient.Builder prometheus = builder.baseUrl(properties.getUrl());
			if (StringUtils.hasText(properties.getUsername())) {
				prometheus = prometheus.defaultHeaders((headers) -> headers.setBasicAuth(properties.getUsername(),
						(properties.getPassword() != null) ? properties.getPassword() : ""));
			}
			else if (StringUtils.hasText(properties.getToken())) {
				prometheus = prometheus.defaultHeaders((headers) -> headers.setBearerAuth(properties.getToken()));
			}
			return prometheus.build();
		}

		/**
		 * Registers the source querying Prometheus.
		 * @param prometheusServiceGraphWebClient the client querying Prometheus
		 * @param properties the Prometheus configuration
		 * @return the Prometheus service graph source
		 */
		@Bean
		@ConditionalOnMissingBean(ServiceGraphSource.class)
		ServiceGraphSource prometheusServiceGraphSource(WebClient prometheusServiceGraphWebClient,
				PrometheusServiceGraphProperties properties) {
			return new PrometheusServiceGraphSource(prometheusServiceGraphWebClient, properties);
		}

	}

}
