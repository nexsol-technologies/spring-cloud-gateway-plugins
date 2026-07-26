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

/**
 * The exact paths a UI view serves, contributed to the security filter chain of the
 * shell.
 * <p>
 * Each active view declares its own endpoints and static assets, so that only what is
 * actually served is opened. Paths are matched exactly on purpose: a pattern such as
 * {@code /ui/**} would also open any gateway route declared under {@code /ui}, which the
 * UI does not serve and did not intend to expose.
 *
 * @param paths the exact paths served by the contributing view
 */
public record UiSecuredPaths(List<String> paths) {

	/**
	 * Creates a contribution over the given exact paths.
	 * @param paths the exact paths served by the contributing view
	 */
	public UiSecuredPaths(String... paths) {
		this(List.of(paths));
	}

}
