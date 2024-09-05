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

package ch.nexsol.gateway.oauth2.resourceserver.multitenancy.condition;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import ch.nexsol.gateway.oauth2.resourceserver.multitenancy.ResourceServerMultiTenantProperties.OAuth2ResourceServerProperties;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class MultitenancyConfiguredCondition extends SpringBootCondition {

	private static final Bindable<List<OAuth2ResourceServerProperties>> STRING_REGISTRATION_LIST = Bindable
		.listOf(OAuth2ResourceServerProperties.class);

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		ConditionMessage.Builder message = ConditionMessage.forCondition("OAUTH2 Multitenancy registered");
		List<OAuth2ResourceServerProperties> registrations = getRegistrations(context.getEnvironment());
		if (!registrations.isEmpty()) {
			return ConditionOutcome.match(message.because(registrations.size() + " clients : "
					+ registrations.stream()
						.map(OAuth2ResourceServerProperties::getId)
						.collect(Collectors.joining(", "))));
		}
		return ConditionOutcome.noMatch(message.notAvailable(registrations.size() + " clients"));
	}

	private List<OAuth2ResourceServerProperties> getRegistrations(Environment environment) {
		return Binder.get(environment)
			.bind("spring.security.oauth2.resourceserver.multitenant", STRING_REGISTRATION_LIST)
			.orElse(Collections.emptyList());
	}

}
