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

package ch.nexsol.gateway.commons;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.util.StringUtils;

/**
 * Names the running instance, so figures collected locally can say whose they are.
 * <p>
 * Resolved once at startup: the configured id wins, then the {@code HOSTNAME} environment
 * variable, which Kubernetes sets to the pod name, then the host name itself. The lookup
 * can hit DNS, which is why it is not done per request.
 */
public class InstanceIdentity {

	private static final Logger LOG = LoggerFactory.getLogger(InstanceIdentity.class);

	private static final String UNKNOWN = "unknown";

	private final String id;

	/**
	 * Resolve the identity of this instance.
	 * @param configuredId the explicitly configured id, {@code null} when unset
	 */
	public InstanceIdentity(String configuredId) {
		this.id = resolve(configuredId);
	}

	private static String resolve(String configuredId) {
		if (StringUtils.hasText(configuredId)) {
			return configuredId;
		}
		String hostname = System.getenv("HOSTNAME");
		if (StringUtils.hasText(hostname)) {
			return hostname;
		}
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			LOG.debug("Could not resolve the host name of this instance", ex);
			return UNKNOWN;
		}
	}

	/**
	 * @return the identifier of this instance
	 */
	public String id() {
		return this.id;
	}

}
