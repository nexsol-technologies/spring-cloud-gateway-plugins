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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Matches when there is something to authenticate the callers of a plugin against: the
 * local user or the issuer the plugin is given under its own prefix, else the user
 * directory, the authentication manager or the {@code ReactiveJwtDecoder} of the
 * application.
 * <p>
 * A chain contributed with none of them would ask for a principal nobody can present, and
 * turn adding the plugin into an outage. A blank value counts as no value, which is what
 * the helpers building the chain make of it too.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnPluginAuthenticationCondition.class)
public @interface ConditionalOnPluginAuthentication {

	/**
	 * The prefix the {@link PluginSecurityProperties} of the plugin are bound under,
	 * without a trailing dot.
	 * @return the prefix
	 */
	String prefix();

}
