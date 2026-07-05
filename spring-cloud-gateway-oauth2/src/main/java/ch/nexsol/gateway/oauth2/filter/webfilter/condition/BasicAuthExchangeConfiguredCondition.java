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

package ch.nexsol.gateway.oauth2.filter.webfilter.condition;

import ch.nexsol.gateway.oauth2.properties.BasicAuthExchangeToAccessTokenProperties;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches when at least one Basic-auth to access-token exchange token URI
 * is configured, enabling the related beans.
 */
public class BasicAuthExchangeConfiguredCondition extends SpringBootCondition {

	private static final Bindable<BasicAuthExchangeToAccessTokenProperties> PROPERTIES = Bindable
		.of(BasicAuthExchangeToAccessTokenProperties.class);

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		ConditionMessage.Builder message = ConditionMessage.forCondition("Basic Auth to OAUTH2 Configured Condition");
		BasicAuthExchangeToAccessTokenProperties properties = getProperties(context.getEnvironment());
		if (properties != null && !properties.getTokenUris().isEmpty()) {
			return ConditionOutcome
				.match(message.foundExactly("registered basic auth configuration exchange to oauth2 " + properties));
		}
		return ConditionOutcome
			.noMatch(message.notAvailable("registered basic auth configuration exchange to oauth2 "));
	}

	private BasicAuthExchangeToAccessTokenProperties getProperties(Environment environment) {
		return Binder.get(environment)
			.bind("spring.cloud.gateway.server.webflux.webfilter.basicauth-exchange-oauth2", PROPERTIES)
			.orElse(null);
	}

}
