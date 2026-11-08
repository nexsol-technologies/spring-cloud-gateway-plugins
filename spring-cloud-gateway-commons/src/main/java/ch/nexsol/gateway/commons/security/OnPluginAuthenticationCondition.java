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

package ch.nexsol.gateway.commons.security;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * The condition behind {@link ConditionalOnPluginAuthentication}.
 * <p>
 * The properties are bound rather than read, so a value is tested for text: a
 * {@code @ConditionalOnProperty} would take the blank {@code password:} of a
 * configuration template as a value and contribute a chain with no way through. The beans
 * are looked up by name of type, so the condition loads none of the Spring Security
 * classes itself.
 */
class OnPluginAuthenticationCondition extends SpringBootCondition implements ConfigurationCondition {

	private static final List<String> APPLICATION_TYPES = List.of(
			"org.springframework.security.core.userdetails.ReactiveUserDetailsService",
			"org.springframework.security.authentication.ReactiveAuthenticationManager",
			"org.springframework.security.oauth2.jwt.ReactiveJwtDecoder");

	@Override
	public ConfigurationPhase getConfigurationPhase() {
		return ConfigurationPhase.REGISTER_BEAN;
	}

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes = metadata
			.getAnnotationAttributes(ConditionalOnPluginAuthentication.class.getName());
		String prefix = (String) attributes.get("prefix");
		ConditionMessage.Builder message = ConditionMessage.forCondition(ConditionalOnPluginAuthentication.class);
		Binder binder = Binder.get(context.getEnvironment());
		String password = prefix + ".user.password";
		if (StringUtils.hasText(binder.bind(password, String.class).orElse(null))) {
			return ConditionOutcome.match(message.found("local user").items(password));
		}
		String issuer = prefix + ".oauth2.resourceserver.jwt.issuer-uri";
		if (StringUtils.hasText(binder.bind(issuer, String.class).orElse(null))) {
			return ConditionOutcome.match(message.found("issuer").items(issuer));
		}
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		for (String typeName : APPLICATION_TYPES) {
			if (!ClassUtils.isPresent(typeName, context.getClassLoader())) {
				continue;
			}
			Class<?> type = ClassUtils.resolveClassName(typeName, context.getClassLoader());
			String[] names = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, type, true, false);
			if (names.length > 0) {
				return ConditionOutcome.match(message.found("bean", "beans").items(List.of(names)));
			}
		}
		return ConditionOutcome
			.noMatch(message.didNotFind("a local user, an issuer or an authentication bean").atAll());
	}

}
