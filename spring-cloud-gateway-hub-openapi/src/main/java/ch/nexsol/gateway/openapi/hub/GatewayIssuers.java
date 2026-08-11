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

package ch.nexsol.gateway.openapi.hub;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * The issuers the gateway already validates its routed traffic against, read from
 * {@code spring.security.oauth2.resourceserver} so that the hub does not ask for them a
 * second time.
 * <p>
 * A token good enough for the traffic is exactly the token the console needs to try an
 * operation, so these are the issuers the aggregated documents advertise unless the hub
 * names its own. Both shapes are read: the multi-tenant list the oauth2 plugin adds,
 * whose {@code id} names each issuer, and the single issuer of the Spring property.
 * <p>
 * The properties are bound from the {@link Environment} rather than injected: the hub
 * does not depend on the oauth2 plugin, and this must work whether or not it is on the
 * classpath.
 */
public final class GatewayIssuers {

	/**
	 * Name the single-issuer configuration is registered under, after the property it is
	 * read from. Something has to name it, since the issuers travel by name and that
	 * shape carries none.
	 */
	public static final String JWT_ISSUER_NAME = "jwt";

	private static final String MULTITENANT = "spring.security.oauth2.resourceserver.multitenant";

	private static final String JWT_ISSUER_URI = "spring.security.oauth2.resourceserver.jwt.issuer-uri";

	private static final String WELL_KNOWN = "/.well-known/openid-configuration";

	private static final Bindable<List<Tenant>> TENANT_LIST = Bindable.listOf(Tenant.class);

	private GatewayIssuers() {
	}

	/**
	 * Returns the OpenID Connect discovery URL of every issuer the gateway is configured
	 * with, by name.
	 * @param environment the environment holding the resource server configuration
	 * @return the discovery URLs by issuer name, empty when the gateway validates nothing
	 */
	public static Map<String, String> from(Environment environment) {
		Map<String, String> issuers = new LinkedHashMap<>();
		Binder binder = Binder.get(environment);
		for (Tenant tenant : binder.bind(MULTITENANT, TENANT_LIST).orElse(Collections.emptyList())) {
			if (StringUtils.hasText(tenant.id()) && StringUtils.hasText(tenant.issuerUri())) {
				issuers.put(tenant.id(), discoveryUrl(tenant.issuerUri()));
			}
		}
		String jwtIssuer = binder.bind(JWT_ISSUER_URI, String.class).orElse(null);
		if (StringUtils.hasText(jwtIssuer)) {
			issuers.put(JWT_ISSUER_NAME, discoveryUrl(jwtIssuer));
		}
		return issuers;
	}

	private static String discoveryUrl(String issuerUri) {
		String issuer = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
		return issuer + WELL_KNOWN;
	}

	/**
	 * One entry of the multi-tenant list, bound structurally rather than through the type
	 * the oauth2 plugin declares, which the hub must not depend on.
	 *
	 * @param id the name of the tenant
	 * @param issuerUri the issuer it validates its tokens against
	 */
	record Tenant(String id, String issuerUri) {
	}

}
