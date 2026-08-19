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

package ch.nexsol.gateway.servicegraph.tempo.autoconfigure;

import ch.nexsol.gateway.servicegraph.ServiceGraphSource;
import ch.nexsol.gateway.servicegraph.autoconfigure.ServiceGraphAutoConfiguration;
import ch.nexsol.gateway.servicegraph.tempo.TempoServiceGraphProperties;
import ch.nexsol.gateway.servicegraph.tempo.TempoServiceGraphSource;

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
 * Registers the Tempo {@link ServiceGraphSource} when
 * {@code service-graph.provider=tempo}. Ordered before the core auto-configuration so its
 * source wins over the local counters.
 */
@AutoConfiguration(before = ServiceGraphAutoConfiguration.class)
@ConditionalOnClass(WebClient.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.service-graph.enabled", matchIfMissing = true)
public class TempoServiceGraphAutoConfiguration {

	/**
	 * Holds the beans behind the provider selector. Two levels because
	 * {@code @ConditionalOnProperty} is not repeatable.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.service-graph.provider", havingValue = "tempo")
	static class TempoSourceConfiguration {

		/**
		 * Binds the Tempo service graph properties.
		 * @return the Tempo service graph properties bean
		 */
		@Bean
		@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.service-graph.tempo")
		TempoServiceGraphProperties tempoServiceGraphProperties() {
			return new TempoServiceGraphProperties();
		}

		/**
		 * Registers the client used to read the series, carrying the configured
		 * credentials. An application whose server needs more than Basic or a static
		 * bearer token declares a {@code tempoServiceGraphWebClient} bean of its own and
		 * it is used instead.
		 * @param builder the application web client builder
		 * @param properties the Tempo configuration
		 * @return the client reading the series
		 */
		@Bean
		@ConditionalOnMissingBean(name = "tempoServiceGraphWebClient")
		WebClient tempoServiceGraphWebClient(WebClient.Builder builder, TempoServiceGraphProperties properties) {
			WebClient.Builder tempo = builder.baseUrl(properties.getUrl());
			if (StringUtils.hasText(properties.getUsername())) {
				tempo = tempo.defaultHeaders((headers) -> headers.setBasicAuth(properties.getUsername(),
						(properties.getPassword() != null) ? properties.getPassword() : ""));
			}
			else if (StringUtils.hasText(properties.getToken())) {
				tempo = tempo.defaultHeaders((headers) -> headers.setBearerAuth(properties.getToken()));
			}
			return tempo.build();
		}

		/**
		 * Registers the source reading the graph of Tempo.
		 * @param tempoServiceGraphWebClient the client reading the series
		 * @param properties the Tempo configuration
		 * @return the Tempo service graph source
		 */
		@Bean
		@ConditionalOnMissingBean(ServiceGraphSource.class)
		ServiceGraphSource tempoServiceGraphSource(WebClient tempoServiceGraphWebClient,
				TempoServiceGraphProperties properties) {
			return new TempoServiceGraphSource(tempoServiceGraphWebClient, properties);
		}

	}

}
