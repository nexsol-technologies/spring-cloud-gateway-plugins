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

package ch.nexsol.gateway.database;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the database-backed route management.
 * <p>
 * They govern what the plugin <em>exposes</em>, never what it reads: whatever is set
 * here, the routes stored in the database keep feeding the gateway. Turning the module
 * off altogether is a different decision, and a different switch.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.routes-database")
public class RoutesDatabaseProperties {

	/**
	 * What the route management exposes over HTTP.
	 * <p>
	 * Named after {@code management.endpoint.<id>.access}, which asks the same question
	 * of an actuator endpoint, and answers it with the same three words.
	 */
	public enum Access {

		/**
		 * Nothing. The controllers are not registered, and the view does not appear in
		 * the console. The database keeps feeding the gateway its routes, which are then
		 * managed wherever they come from &mdash; a migration, another environment.
		 */
		NONE,

		/**
		 * Reading only. The routes can be listed and read; creating, changing and
		 * deleting one answers {@code 405 Method Not Allowed}, and the view drops the
		 * affordances that would have led there.
		 */
		READ_ONLY,

		/**
		 * Everything, which is what the plugin has always done.
		 */
		UNRESTRICTED

	}

	/**
	 * Whether the plugin is wired at all: the route definition locator, the services, the
	 * repositories, and whatever {@link #getAccess() the access} publishes. Turned off,
	 * the database stops feeding the gateway its routes, which is a different decision
	 * from refusing to publish the endpoints that manage them.
	 */
	private boolean enabled = true;

	/**
	 * What the route management exposes over HTTP. Left unrestricted, since that is the
	 * behaviour every gateway using this plugin has today; a gateway whose routes come
	 * from a migration rather than from an operator is better served by
	 * {@link Access#READ_ONLY} or {@link Access#NONE}.
	 */
	private Access access = Access.UNRESTRICTED;

	/**
	 * Whether the plugin contributes the security filter chain closing the endpoints that
	 * change the routing table. Turn it off to secure them from the application instead.
	 */
	private boolean securityChainEnabled = true;

	/**
	 * Returns whether the plugin is wired at all.
	 * @return whether the plugin is wired
	 */
	public boolean isEnabled() {
		return this.enabled;
	}

	/**
	 * Sets whether the plugin is wired at all.
	 * @param enabled whether the plugin is wired
	 */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	/**
	 * Returns what the route management exposes over HTTP.
	 * @return the access
	 */
	public Access getAccess() {
		return this.access;
	}

	/**
	 * Sets what the route management exposes over HTTP.
	 * @param access the access
	 */
	public void setAccess(Access access) {
		this.access = access;
	}

	/**
	 * Returns whether the plugin contributes its security filter chain.
	 * @return whether the chain is contributed
	 */
	public boolean isSecurityChainEnabled() {
		return this.securityChainEnabled;
	}

	/**
	 * Sets whether the plugin contributes its security filter chain.
	 * @param securityChainEnabled whether the chain is contributed
	 */
	public void setSecurityChainEnabled(boolean securityChainEnabled) {
		this.securityChainEnabled = securityChainEnabled;
	}

}
