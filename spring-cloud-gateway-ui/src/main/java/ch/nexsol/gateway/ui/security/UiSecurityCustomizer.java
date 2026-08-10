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

import java.util.List;

import org.springframework.security.config.web.server.ServerHttpSecurity;

/**
 * A contribution to the chain of the console, applied when the console authenticates.
 * <p>
 * It exists so that the OAuth2 parts of the chain live in classes of their own, loaded
 * only when the matching Spring Security modules are on the classpath: a console secured
 * by a local user alone must not need {@code spring-security-oauth2-client} to start.
 */
public interface UiSecurityCustomizer {

	/**
	 * Applies the contribution to the chain being built.
	 * @param http the reactive security builder of the console chain
	 */
	void customize(ServerHttpSecurity http);

	/**
	 * The extra paths the contribution needs the chain to match, on top of the ones the
	 * views declared. They are permitted, as the endpoints of an authentication exchange
	 * have to be reachable before there is a principal.
	 * @return the extra paths, empty by default
	 */
	default List<String> paths() {
		return List.of();
	}

}
