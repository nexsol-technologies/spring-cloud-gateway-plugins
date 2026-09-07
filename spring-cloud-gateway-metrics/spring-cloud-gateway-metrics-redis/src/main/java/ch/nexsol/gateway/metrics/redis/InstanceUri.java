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
 * Redis is not a registry. So the address is guessed from the port the server actually
 * bound and the host this machine reports, which is right on a flat network and wrong
 * behind anything that rewrites addresses. Wherever it is wrong, {@code instance-uri}
 * states it and wins &mdash; that property is the answer for a container, not an
 * afterthought.
 * <p>
 * What reads this is the console: an instance publishing no address is one it cannot
 * offer to read the Actuator endpoints of, and cannot include when a logging level is set
 * across the fleet.
 */
public class InstanceUri implements ApplicationListener<WebServerInitializedEvent> {

	private static final Logger LOG = LoggerFactory.getLogger(InstanceUri.class);

	private final String configured;

	private final String scheme;

	private volatile String resolved;

	/**
	 * Creates the resolver.
	 * @param configured the address the operator declared, used as-is when set
	 * @param scheme the scheme to build the guessed address with
	 */
	public InstanceUri(String configured, String scheme) {
		this.configured = configured;
		this.scheme = StringUtils.hasText(scheme) ? scheme : "http";
	}

	@Override
	public void onApplicationEvent(WebServerInitializedEvent event) {
		if (StringUtils.hasText(this.configured)) {
			return;
		}
		// The port the server bound, not the one it was asked to: started on port 0, a
		// gateway takes whatever was free, and that is where it is reached.
		this.resolved = this.scheme + "://" + host() + ":" + event.getWebServer().getPort();
	}

	/**
	 * @return the address to publish, {@code null} while the server has not started and
	 * no address was configured
	 */
	public String get() {
		return StringUtils.hasText(this.configured) ? this.configured : this.resolved;
	}

	private static String host() {
		try {
			return InetAddress.getLocalHost().getHostAddress();
		}
		catch (UnknownHostException ex) {
			// A machine that cannot name itself still publishes something reachable from
			// its own host, which is where a single-node deployment reads it from.
			LOG.debug("Could not resolve the host of this instance", ex);
			return "localhost";
		}
	}

}
