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

package ch.nexsol.gateway.audit.r2dbc.autoconfigure;

import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditEventSerializer;
import ch.nexsol.gateway.audit.autoconfigure.AuditAutoConfiguration;
import ch.nexsol.gateway.audit.r2dbc.R2dbcAuditEventPublisher;
import ch.nexsol.gateway.audit.r2dbc.R2dbcAuditProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Registers the R2DBC {@link AuditEventPublisher} when {@code audit.provider=r2dbc} and a
 * {@link DatabaseClient} is available. Ordered after {@link R2dbcAutoConfiguration} so
 * the auto-configured client is already defined, and before the core auto-configuration
 * so its publisher wins over the default one.
 */
@AutoConfiguration(after = R2dbcAutoConfiguration.class, before = AuditAutoConfiguration.class)
@ConditionalOnClass(DatabaseClient.class)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.audit.provider", havingValue = "r2dbc")
public class R2dbcAuditAutoConfiguration {

	/**
	 * Binds the R2DBC audit properties.
	 * @return the R2DBC audit properties bean
	 */
	@Bean
	@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.audit.r2dbc")
	R2dbcAuditProperties r2dbcAuditProperties() {
		return new R2dbcAuditProperties();
	}

	/**
	 * Registers the R2DBC audit publisher.
	 * @param databaseClient the R2DBC database client
	 * @param properties the R2DBC audit properties
	 * @param objectMapper the object mapper used to render the attributes
	 * @return the R2DBC audit publisher bean
	 */
	@Bean
	@ConditionalOnMissingBean(AuditEventPublisher.class)
	@ConditionalOnSingleCandidate(DatabaseClient.class)
	AuditEventPublisher r2dbcAuditEventPublisher(DatabaseClient databaseClient, R2dbcAuditProperties properties,
			ObjectMapper objectMapper) {
		return new R2dbcAuditEventPublisher(databaseClient, properties.getTable(),
				new AuditEventSerializer(objectMapper));
	}

}
