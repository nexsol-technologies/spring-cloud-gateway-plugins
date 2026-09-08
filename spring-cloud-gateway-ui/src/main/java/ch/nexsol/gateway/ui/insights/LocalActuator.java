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

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.util.StringUtils;

/**
 * Where this instance serves its own Actuator endpoints.
 * <p>
 * Nothing here is taken from the request being served. That request arrived through
 * whatever fronts this gateway &mdash; an ingress, a load balancer, a reverse proxy
 * &mdash; and its host and port are that front door's, not this instance's: reading them
 * back sends the instance out through the front door to reach itself, which either
 * refuses the connection or lands on a different instance entirely.
 * <p>
 * It calls itself instead, on the loopback and on the port its endpoints are actually
 * bound to: {@code management.server.port} when the endpoints have one of their own,
 * otherwise the port the application server bound &mdash; the bound one, since
 * {@code server.port: 0} takes whatever was free. {@code management.server.address} is
 * honoured where a deployment pinned the management server to one interface, loopback not
 * being among them then.
 */
public class LocalActuator implements ApplicationListener<WebServerInitializedEvent> {

	private final Integer managementPort;

	private final String managementAddress;

	private final String basePath;

	private volatile int applicationPort;

	/**
	 * Creates the resolver from the management configuration of this instance.
	 * @param managementPort {@code management.server.port}, {@code null} when the
	 * endpoints share the port of the application
	 * @param managementAddress {@code management.server.address}, empty when the
	 * management server binds every interface
	 * @param serverBasePath {@code management.server.base-path}, which applies only when
	 * the endpoints have a port of their own
	 * @param webBasePath {@code management.endpoints.web.base-path}
	 */
	public LocalActuator(Integer managementPort, String managementAddress, String serverBasePath, String webBasePath) {
		this.managementPort = managementPort;
		this.managementAddress = managementAddress;
		String prefix = (managementPort != null && StringUtils.hasText(serverBasePath)) ? serverBasePath : "";
		this.basePath = prefix + (StringUtils.hasText(webBasePath) ? webBasePath : "");
	}

	@Override
	public void onApplicationEvent(WebServerInitializedEvent event) {
		// The port bound rather than the port requested, and the application server
		// rather
		// than the management one: the management server publishes an event of its own,
		// which this deliberately ignores — its port is already known from configuration.
		if (this.applicationPort == 0) {
			this.applicationPort = event.getWebServer().getPort();
		}
	}

	/**
	 * @return the path the endpoints are served under, base paths included
	 */
	public String basePath() {
		return this.basePath;
	}

	/**
	 * The address of one endpoint on this instance.
	 * @param endpoint the endpoint, e.g. {@code loggers}
	 * @param basePath the path the endpoints are served under
	 * @return the address to read it at
	 */
	public String url(String endpoint, String basePath) {
		String host = StringUtils.hasText(this.managementAddress) ? this.managementAddress : "localhost";
		int port = (this.managementPort != null) ? this.managementPort : this.applicationPort;
		return "http://" + host + ":" + port + basePath + "/" + endpoint;
	}

}
