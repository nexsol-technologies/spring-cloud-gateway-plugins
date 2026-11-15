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

package ch.nexsol.gateway.mcp.hub;

import java.util.List;
import java.util.Set;

/**
 * The tools a source could read, and what it could not.
 *
 * @param tools the tools of every contract that could be read
 * @param unreadServices the services whose contract could not be read: their tools are
 * missing from the list, not gone
 * @param sourcesRead {@code false} when a route source could not be read, so that the
 * services it documents are not even known
 */
public record ApiCatalog(List<ApiTool> tools, Set<String> unreadServices, boolean sourcesRead) {

	/**
	 * Creates the catalog, copying the collections so a caller cannot change what was
	 * read.
	 * @param tools the tools of every contract that could be read
	 * @param unreadServices the services whose contract could not be read
	 * @param sourcesRead whether every route source was read
	 */
	public ApiCatalog {
		tools = List.copyOf(tools);
		unreadServices = Set.copyOf(unreadServices);
	}

	/**
	 * A catalog read in full.
	 * @param tools the tools
	 * @return the catalog
	 */
	public static ApiCatalog complete(List<ApiTool> tools) {
		return new ApiCatalog(tools, Set.of(), true);
	}

	/**
	 * Whether a tool missing from this catalog may still exist: its service did not
	 * answer, or a route source could not be read.
	 * @param tool a tool registered from an earlier catalog
	 * @return {@code true} when the tool is to be kept
	 */
	public boolean mayStillServe(ApiTool tool) {
		return !this.sourcesRead || this.unreadServices.contains(tool.operation().service());
	}

}
