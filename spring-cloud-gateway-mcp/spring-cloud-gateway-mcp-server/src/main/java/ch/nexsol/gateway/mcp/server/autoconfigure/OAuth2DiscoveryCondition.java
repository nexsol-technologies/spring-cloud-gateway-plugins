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

package ch.nexsol.gateway.mcp.server.autoconfigure;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches when an authorization server is known for the MCP endpoint: the ones the plugin
 * is given, else the issuer the endpoint validates its tokens against, else the issuer of
 * the console, else the issuer of the application. Without one there is nothing to
 * publish, and the endpoint keeps answering a bare {@code 401}.
 */
class OAuth2DiscoveryCondition extends SpringBootCondition {

	static final String AUTHORIZATION_SERVERS = "spring.cloud.gateway.server.webflux.mcp.security.oauth2.authorization-servers";

	static final String MCP_ISSUER = "spring.cloud.gateway.server.webflux.mcp.security.oauth2.resourceserver.jwt.issuer-uri";

	static final String CONSOLE_ISSUER = "spring.cloud.gateway.server.webflux.ui.security.oauth2.resourceserver.jwt.issuer-uri";

	static final String APPLICATION_ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		List<String> servers = authorizationServers(context.getEnvironment());
		ConditionMessage.Builder message = ConditionMessage.forCondition("MCP OAuth discovery");
		if (servers.isEmpty()) {
			return ConditionOutcome.noMatch(message.didNotFind("an authorization server").atAll());
		}
		return ConditionOutcome.match(message.found("authorization server", "authorization servers").items(servers));
	}

	/**
	 * Resolves the authorization servers to publish.
	 * @param environment the environment
	 * @return the servers, empty when none is known
	 */
	static List<String> authorizationServers(Environment environment) {
		List<String> declared = Binder.get(environment)
			.bind(AUTHORIZATION_SERVERS, Bindable.listOf(String.class))
			.orElse(List.of());
		if (!declared.isEmpty()) {
			return declared;
		}
		for (String key : List.of(MCP_ISSUER, CONSOLE_ISSUER, APPLICATION_ISSUER)) {
			String issuer = environment.getProperty(key);
			if (StringUtils.hasText(issuer)) {
				return List.of(issuer);
			}
		}
		return List.of();
	}

}
