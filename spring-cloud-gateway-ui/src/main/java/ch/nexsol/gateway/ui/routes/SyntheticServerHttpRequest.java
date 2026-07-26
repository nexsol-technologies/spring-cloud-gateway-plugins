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

package ch.nexsol.gateway.ui.routes;

import java.net.URI;
import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

/**
 * Request describing the call the route tester evaluates the predicates against. It is
 * not connected to any client: the body is always empty and there is no native request
 * behind it.
 * <p>
 * The path, query parameters and cookies are derived from the URI and headers by the
 * superclass, so the route predicates see exactly what they would see for a real call.
 */
final class SyntheticServerHttpRequest extends AbstractServerHttpRequest {

	SyntheticServerHttpRequest(HttpMethod method, URI uri, HttpHeaders headers) {
		super(method, uri, null, headers);
	}

	@Override
	protected MultiValueMap<String, HttpCookie> initCookies() {
		MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
		List<String> headers = getHeaders().get(HttpHeaders.COOKIE);
		if (headers == null) {
			return cookies;
		}
		for (String header : headers) {
			for (String pair : StringUtils.tokenizeToStringArray(header, ";")) {
				int separator = pair.indexOf('=');
				String name = (separator >= 0) ? pair.substring(0, separator).trim() : pair.trim();
				String value = (separator >= 0) ? pair.substring(separator + 1).trim() : "";
				if (StringUtils.hasText(name)) {
					cookies.add(name, new HttpCookie(name, value));
				}
			}
		}
		return cookies;
	}

	@Override
	protected SslInfo initSslInfo() {
		return null;
	}

	@Override
	public <T> T getNativeRequest() {
		throw new UnsupportedOperationException("The route tester request is not backed by a native request");
	}

	@Override
	public Flux<DataBuffer> getBody() {
		return Flux.empty();
	}

}
