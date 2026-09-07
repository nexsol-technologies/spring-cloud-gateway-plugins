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

package ch.nexsol.gateway.ui.insights;

import java.net.URI;

import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

/**
 * Where this instance serves its own Actuator endpoints.
 * <p>
 * Not necessarily where it serves the console. {@code management.server.port} moves the
 * endpoints to a port of their own — a common thing to do, since it lets a deployment
 * expose the console and keep the endpoints off the public interface — and
 * {@code management.server.base-path} and {@code management.endpoints.web.base-path} move
 * them again. Reading them at the port and path the console request arrived on works only
 * where none of that was configured.
 * <p>
 * The host is still taken from the request: it is the name this instance is actually
 * reached by, which no property records.
 */
public class LocalActuator {

	private final Integer port;

	private final String basePath;

	/**
	 * Creates the resolver from the management configuration of this instance.
	 * @param port {@code management.server.port}, {@code null} when the endpoints share
	 * the port of the application
	 * @param serverBasePath {@code management.server.base-path}, which applies only when
	 * the endpoints have a port of their own
	 * @param webBasePath {@code management.endpoints.web.base-path}
	 */
	public LocalActuator(Integer port, String serverBasePath, String webBasePath) {
		this.port = port;
		String prefix = (port != null && StringUtils.hasText(serverBasePath)) ? serverBasePath : "";
		this.basePath = prefix + (StringUtils.hasText(webBasePath) ? webBasePath : "");
	}

	/**
	 * @return the path the endpoints are served under, base paths included
	 */
	public String basePath() {
		return this.basePath;
	}

	/**
	 * The address of one endpoint on this instance.
	 * @param exchange the request being served, which carries the host this instance is
	 * reached by
	 * @param endpoint the endpoint, e.g. {@code loggers}
	 * @param basePath the path the endpoints are served under
	 * @return the address to read it at
	 */
	public String url(ServerWebExchange exchange, String endpoint, String basePath) {
		URI uri = exchange.getRequest().getURI();
		int resolved = (this.port != null) ? this.port : uri.getPort();
		String authority = uri.getHost() + ((resolved > 0) ? ":" + resolved : "");
		return uri.getScheme() + "://" + authority + basePath + "/" + endpoint;
	}

}
