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

package ch.nexsol.gateway.metrics.redis;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.util.StringUtils;

/**
 * Where this instance is reachable, for the figures it publishes to Redis.
 * <p>
 * An instance genuinely does not know this: behind a proxy, a container or a load
 * balancer, the address it binds is not the address anything reaches it on. That is why
 * the figures carry no address of their own, and why the discovery provider fills it in
 * from the registry &mdash; a registry is exactly the thing that knows.
 * <p>
 * Redis is not a registry. So the address is built from the name this instance answers to
 * on its own network &mdash; {@code HOSTNAME}, which a container runtime sets to a name
 * the other containers resolve &mdash; and the port its Actuator endpoints are bound to.
 * Never from anything the console was reached through: an ingress or a load balancer is
 * the way in from outside, not the way one instance reaches another.
 * <p>
 * Where that address is wrong all the same, {@code instance-uri} states it and wins.
 * <p>
 * What reads this is the console: an instance publishing no address is one it cannot
 * offer to read the Actuator endpoints of, and cannot include when a logging level is set
 * across the fleet.
 */
public class InstanceUri implements ApplicationListener<WebServerInitializedEvent> {

	private static final Logger LOG = LoggerFactory.getLogger(InstanceUri.class);

	private final String configured;

	private final String scheme;

	private final Integer managementPort;

	private volatile String resolved;

	/**
	 * Creates the resolver.
	 * @param configured the address the operator declared, used as-is when set
	 * @param scheme the scheme to build the guessed address with
	 * @param managementPort {@code management.server.port}, {@code null} when the
	 * endpoints share the port of the application
	 */
	public InstanceUri(String configured, String scheme, Integer managementPort) {
		this.configured = configured;
		this.scheme = StringUtils.hasText(scheme) ? scheme : "http";
		this.managementPort = managementPort;
	}

	@Override
	public void onApplicationEvent(WebServerInitializedEvent event) {
		if (StringUtils.hasText(this.configured)) {
			return;
		}
		// The port the endpoints are on, which is what this address is read for: the
		// management port when they have one of their own, otherwise the port the server
		// bound — the bound one, since `server.port: 0` takes whatever was free.
		int port = (this.managementPort != null) ? this.managementPort : event.getWebServer().getPort();
		this.resolved = this.scheme + "://" + host() + ":" + port;
	}

	/**
	 * @return the address to publish, {@code null} while the server has not started and
	 * no address was configured
	 */
	public String get() {
		return StringUtils.hasText(this.configured) ? this.configured : this.resolved;
	}

	/**
	 * The name this instance answers to on its own network.
	 * <p>
	 * {@code HOSTNAME} first: a container is reachable from the others by that name,
	 * which is what Docker and Kubernetes put there, and it survives the address changing
	 * under it. The host name and then the address follow for a deployment that sets no
	 * such variable.
	 */
	private static String host() {
		String hostname = System.getenv("HOSTNAME");
		if (StringUtils.hasText(hostname)) {
			return hostname;
		}
		try {
			InetAddress local = InetAddress.getLocalHost();
			return StringUtils.hasText(local.getHostName()) ? local.getHostName() : local.getHostAddress();
		}
		catch (UnknownHostException ex) {
			// A machine that cannot name itself still publishes something reachable from
			// its own host, which is where a single-node deployment reads it from.
			LOG.debug("Could not resolve the host of this instance", ex);
			return "localhost";
		}
	}

}
