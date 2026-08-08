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

package ch.nexsol.gateway.metrics;

import reactor.netty.http.client.HttpClient;

import org.springframework.cloud.gateway.config.HttpClientCustomizer;

/**
 * Turns on the Reactor Netty instrumentation the gateway leaves off.
 * <p>
 * The event loop, HTTP client and buffer allocator counters only come into existence once
 * the transport carries a metrics recorder, and the gateway never asks for one: its HTTP
 * client factory never calls {@code metrics(...)}, and no property exposes it. Without
 * this customizer the counters simply do not exist, however the meter registry is
 * queried.
 * <p>
 * This is the one place where the metrics plugin changes what the gateway does at runtime
 * rather than only reading what it already publishes, which is why it is guarded by an
 * explicit property that defaults to off.
 */
public class GatewayHttpClientInstrumentation implements HttpClientCustomizer {

	/**
	 * The single value every request URI is folded to.
	 * <p>
	 * {@link HttpClient} has no plain {@code metrics(boolean)} overload: the URI mapper
	 * is mandatory, because the {@code uri} tag would otherwise carry the downstream path
	 * and create one meter per distinct path &mdash; on a gateway, per distinct path of
	 * every service behind it. Folding them all to one value keeps the
	 * {@code remote.address} tag, which is what tells the downstreams apart and the only
	 * distinction worth paying for here.
	 */
	public static final String AGGREGATE_URI = "gateway";

	@Override
	public HttpClient customize(HttpClient httpClient) {
		return httpClient.metrics(true, (uri) -> AGGREGATE_URI);
	}

}
