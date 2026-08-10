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

package ch.nexsol.gateway.ui.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the security of the console.
 * <p>
 * The console is open by default, as it has always been: the chain it contributes permits
 * the paths its views serve so the shell keeps working behind the authentication of the
 * application. Switching {@link Mode#AUTHENTICATED} on puts a login page in front of it
 * instead, backed by a local user, an OpenID Connect provider, or both.
 */
@ConfigurationProperties(prefix = "spring.cloud.gateway.server.webflux.ui.security")
public class GatewayUiSecurityProperties {

	/**
	 * How the chain of the console treats the paths its views serve.
	 */
	public enum Mode {

		/**
		 * The paths of the console are open. The shell is reachable behind whatever the
		 * application authenticates elsewhere.
		 */
		PERMIT_ALL,

		/**
		 * The paths of the console require an authenticated principal, obtained through
		 * the login page the plugin serves.
		 */
		AUTHENTICATED

	}

	/**
	 * How the console treats its own paths.
	 */
	private Mode mode = Mode.PERMIT_ALL;

	/**
	 * The local user the login form authenticates against. Left without a password, no
	 * local user is declared and the console authenticates against whatever the
	 * application configured.
	 */
	private final User user = new User();

	/**
	 * The claim the roles of a token are read from, as a dotted path into the claim set
	 * ({@code roles}, {@code realm_access.roles}). Unset, the authorities are the ones
	 * the default converters produce, which is what {@code required-roles} then matches
	 * on.
	 */
	private String rolesClaim;

	/**
	 * The roles a principal must hold to reach the console, without their {@code ROLE_}
	 * prefix. Empty, any authenticated principal is let through.
	 */
	private List<String> requiredRoles = new ArrayList<>();

	/**
	 * Returns how the console treats its own paths.
	 * @return the mode
	 */
	public Mode getMode() {
		return this.mode;
	}

	/**
	 * Sets how the console treats its own paths.
	 * @param mode the mode
	 */
	public void setMode(Mode mode) {
		this.mode = mode;
	}

	/**
	 * Returns the local user the login form authenticates against.
	 * @return the local user
	 */
	public User getUser() {
		return this.user;
	}

	/**
	 * Returns the dotted path the roles of a token are read from.
	 * @return the claim path, or {@code null} when the default authorities are used
	 */
	public String getRolesClaim() {
		return this.rolesClaim;
	}

	/**
	 * Sets the dotted path the roles of a token are read from.
	 * @param rolesClaim the claim path
	 */
	public void setRolesClaim(String rolesClaim) {
		this.rolesClaim = rolesClaim;
	}

	/**
	 * Returns the roles a principal must hold to reach the console.
	 * @return the required roles, empty when any authenticated principal is let through
	 */
	public List<String> getRequiredRoles() {
		return this.requiredRoles;
	}

	/**
	 * Sets the roles a principal must hold to reach the console.
	 * @param requiredRoles the required roles
	 */
	public void setRequiredRoles(List<String> requiredRoles) {
		this.requiredRoles = requiredRoles;
	}

	/**
	 * The local user the login form authenticates against, held by the chain of the
	 * console alone: it is not registered as a {@code ReactiveUserDetailsService}, so it
	 * neither competes with nor replaces the authentication the rest of the application
	 * uses.
	 */
	public static class User {

		/**
		 * The name the user signs in with.
		 */
		private String name = "admin";

		/**
		 * The password the user signs in with, either in clear text or prefixed with the
		 * id of the encoder that produced it ({@code {bcrypt}$2a$...}). Unset, no local
		 * user is declared.
		 */
		private String password;

		/**
		 * The roles the user holds, without their {@code ROLE_} prefix.
		 */
		private List<String> roles = new ArrayList<>(List.of("ADMIN"));

		/**
		 * Returns the name the user signs in with.
		 * @return the user name
		 */
		public String getName() {
			return this.name;
		}

		/**
		 * Sets the name the user signs in with.
		 * @param name the user name
		 */
		public void setName(String name) {
			this.name = name;
		}

		/**
		 * Returns the password the user signs in with.
		 * @return the password, or {@code null} when no local user is declared
		 */
		public String getPassword() {
			return this.password;
		}

		/**
		 * Sets the password the user signs in with.
		 * @param password the password
		 */
		public void setPassword(String password) {
			this.password = password;
		}

		/**
		 * Returns the roles the user holds.
		 * @return the roles, without their {@code ROLE_} prefix
		 */
		public List<String> getRoles() {
			return this.roles;
		}

		/**
		 * Sets the roles the user holds.
		 * @param roles the roles, without their {@code ROLE_} prefix
		 */
		public void setRoles(List<String> roles) {
			this.roles = roles;
		}

	}

}
