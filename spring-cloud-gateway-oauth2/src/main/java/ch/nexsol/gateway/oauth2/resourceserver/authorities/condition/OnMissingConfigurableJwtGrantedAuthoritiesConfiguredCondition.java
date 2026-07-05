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

package ch.nexsol.gateway.oauth2.resourceserver.authorities.condition;

import java.util.Collections;
import java.util.List;

import ch.nexsol.gateway.oauth2.resourceserver.ResourceServerPluginsProperties.JsonPathGrantedAuthorityProperties;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches when no JSON path granted-authorities mapping is configured,
 * selecting the default JWT granted authorities converter.
 *
 * @author guerricmerle
 */
public class OnMissingConfigurableJwtGrantedAuthoritiesConfiguredCondition extends SpringBootCondition {

	private static final Bindable<List<JsonPathGrantedAuthorityProperties>> STRING_REGISTRATION_LIST = Bindable
		.listOf(JsonPathGrantedAuthorityProperties.class);

	/**
	 * {@inheritDoc}
	 */
	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		ConditionMessage.Builder message = ConditionMessage
			.forCondition("ConfigurableJwtGrantedAuthorities registered");
		List<JsonPathGrantedAuthorityProperties> registrations = getRegistrations(context.getEnvironment());
		if (registrations.isEmpty()) {
			return ConditionOutcome.match(message.because("no registered granted authorites mapping with jsonpath"));
		}
		return ConditionOutcome
			.noMatch(message.because(registrations.size() + " granted authorites mapping with jsonpath registered"));
	}

	private List<JsonPathGrantedAuthorityProperties> getRegistrations(Environment environment) {
		return Binder.get(environment)
			.bind("spring.security.oauth2.resourceserver.granted-authorites-mapping", STRING_REGISTRATION_LIST)
			.orElse(Collections.emptyList());
	}

}
