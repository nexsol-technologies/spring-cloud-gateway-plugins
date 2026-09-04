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

package ch.nexsol.gateway.metrics.prometheus.autoconfigure;

import ch.nexsol.gateway.commons.CodecLimits;
import ch.nexsol.gateway.metrics.InstanceMetricsSource;
import ch.nexsol.gateway.metrics.MetricsProperties;
import ch.nexsol.gateway.metrics.RouteMetricsSource;
import ch.nexsol.gateway.metrics.autoconfigure.MetricsAutoConfiguration;
import ch.nexsol.gateway.metrics.prometheus.PrometheusInstanceMetricsSource;
import ch.nexsol.gateway.metrics.prometheus.PrometheusMetricsProperties;
import ch.nexsol.gateway.metrics.prometheus.PrometheusRouteMetricsSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Registers the Prometheus {@link RouteMetricsSource} when
 * {@code metrics.provider=prometheus}. Ordered before the core auto-configuration so its
 * source wins over the local meter registry.
 */
@AutoConfiguration(before = MetricsAutoConfiguration.class)
@ConditionalOnClass(WebClient.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.enabled", matchIfMissing = true)
public class PrometheusMetricsAutoConfiguration {

	/**
	 * Holds the beans behind the provider selector.
	 * <p>
	 * The two conditions sit on separate levels because {@code @ConditionalOnProperty} is
	 * not repeatable: the plugin must be enabled &mdash; this source reads the shared
	 * {@link MetricsProperties}, which the core only declares then &mdash; and the
	 * provider must be this one.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.metrics.provider", havingValue = "prometheus")
	static class PrometheusSourceConfiguration {

		/**
		 * Binds the Prometheus metrics properties.
		 * @return the Prometheus metrics properties bean
		 */
		@Bean
		@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.metrics.prometheus")
		PrometheusMetricsProperties prometheusMetricsProperties() {
			return new PrometheusMetricsProperties();
		}

		/**
		 * Registers the client used to query Prometheus, carrying the configured
		 * credentials.
		 * <p>
		 * Declared as its own bean so an application whose Prometheus needs more than
		 * Basic or a static bearer token &mdash; mTLS, OAuth2 client credentials, a
		 * service account token that rotates &mdash; can declare a
		 * {@code prometheusMetricsWebClient} bean of its own and have it used instead.
		 * The credentials are set on a client dedicated to Prometheus rather than through
		 * a {@code WebClientCustomizer}, which would put them on every client of the
		 * application.
		 * @param builder the application web client builder
		 * @param properties the Prometheus configuration
		 * @return the client querying Prometheus
		 */
		@Bean
		@ConditionalOnMissingBean(name = "prometheusMetricsWebClient")
		WebClient prometheusMetricsWebClient(WebClient.Builder builder, PrometheusMetricsProperties properties) {
			WebClient.Builder prometheus = builder.baseUrl(properties.getUrl());
			DataSize maxResponseSize = properties.getMaxResponseSize();
			if (maxResponseSize != null) {
				prometheus = prometheus.codecs((codecs) -> codecs.defaultCodecs()
					.maxInMemorySize(CodecLimits.maxInMemoryBytes(maxResponseSize)));
			}
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
		 * @param prometheusMetricsWebClient the client querying Prometheus
		 * @param properties the Prometheus configuration
		 * @param metricsProperties the shared metrics configuration
		 * @return the Prometheus route metrics source
		 */
		@Bean
		@ConditionalOnMissingBean(RouteMetricsSource.class)
		RouteMetricsSource prometheusRouteMetricsSource(WebClient prometheusMetricsWebClient,
				PrometheusMetricsProperties properties, MetricsProperties metricsProperties) {
			return new PrometheusRouteMetricsSource(prometheusMetricsWebClient, properties, metricsProperties);
		}

		/**
		 * Registers the source listing the instances Prometheus heard from recently.
		 * @param prometheusMetricsWebClient the client querying Prometheus
		 * @param properties the Prometheus configuration
		 * @return the Prometheus instance metrics source
		 */
		@Bean
		@ConditionalOnMissingBean(InstanceMetricsSource.class)
		InstanceMetricsSource prometheusInstanceMetricsSource(WebClient prometheusMetricsWebClient,
				PrometheusMetricsProperties properties) {
			return new PrometheusInstanceMetricsSource(prometheusMetricsWebClient, properties);
		}

	}

}
