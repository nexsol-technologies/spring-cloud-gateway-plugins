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

package ch.nexsol.gateway.audit.autoconfigure;

import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventFactory;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.DefaultAuditEventPublisher;
import ch.nexsol.gateway.audit.factory.AuditGatewayFilterFactory;
import ch.nexsol.gateway.audit.webfilter.AuditWebFilter;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(AuditAutoConfiguration.class));

	@Test
	void registersDefaultBeansAndNoWebFilter() {
		this.runner.run((context) -> assertThat(context).hasSingleBean(AuditEventFactory.class)
			.hasSingleBean(AuditGatewayFilterFactory.class)
			.hasSingleBean(AuditEventPublisher.class)
			.getBean(AuditEventPublisher.class)
			.isInstanceOf(DefaultAuditEventPublisher.class));
		this.runner.run((context) -> assertThat(context).doesNotHaveBean(AuditWebFilter.class));
	}

	@Test
	void registersWebFilterWhenEnabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.audit.web-filter.enabled=true")
			.run((context) -> assertThat(context).hasSingleBean(AuditWebFilter.class));
	}

	@Test
	void backsOffWhenDisabled() {
		this.runner.withPropertyValues("spring.cloud.gateway.server.webflux.audit.enabled=false")
			.run((context) -> assertThat(context).doesNotHaveBean(AuditEventFactory.class)
				.doesNotHaveBean(AuditGatewayFilterFactory.class));
	}

	@Test
	void backsOffWhenCustomPublisherProvided() {
		this.runner.withUserConfiguration(CustomPublisherConfiguration.class)
			.run((context) -> assertThat(context).hasSingleBean(AuditEventPublisher.class)
				.doesNotHaveBean(DefaultAuditEventPublisher.class));
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomPublisherConfiguration {

		@Bean
		AuditEventPublisher customAuditEventPublisher() {
			return AuditEvent::attributes;
		}

	}

}
