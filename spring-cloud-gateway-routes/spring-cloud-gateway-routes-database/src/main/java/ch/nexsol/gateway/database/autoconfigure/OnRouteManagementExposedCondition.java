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

package ch.nexsol.gateway.database.autoconfigure;

import ch.nexsol.gateway.database.RoutesDatabaseProperties;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches unless the route management is configured to expose nothing.
 * <p>
 * {@code @ConditionalOnProperty} cannot express "any value but this one", and the value
 * that turns everything off is the one to test against: {@code access=none} is the only
 * setting under which the controllers are not registered at all.
 */
class OnRouteManagementExposedCondition extends SpringBootCondition {

	private static final String PROPERTY = "spring.cloud.gateway.server.webflux.routes-database.access";

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		// Bound rather than read: a value is written read-only, READ_ONLY or read_only
		// and means the same thing, which only the binder of Spring Boot knows.
		RoutesDatabaseProperties.Access access = Binder.get(context.getEnvironment())
			.bind(PROPERTY, RoutesDatabaseProperties.Access.class)
			.orElse(RoutesDatabaseProperties.Access.UNRESTRICTED);
		ConditionMessage.Builder message = ConditionMessage.forCondition("Route management access");
		return (access != RoutesDatabaseProperties.Access.NONE)
				? ConditionOutcome.match(message.because(PROPERTY + " is " + access))
				: ConditionOutcome.noMatch(message.because(PROPERTY + " is none"));
	}

}
