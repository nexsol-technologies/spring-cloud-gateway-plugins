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

import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Tells a request that can be answered with a page from one that cannot.
 * <p>
 * The console answers a refusal with a redirect to its login page, which is only an
 * answer to a browser navigating. A script fetching JSON and an {@code EventSource}
 * subscribing to a stream both follow that redirect and receive the login page under
 * whatever content type was negotiated &mdash; a {@code 200} carrying a sign-in form
 * where the caller expected its data, with nothing in it saying that the request was
 * refused.
 */
final class AcceptedMediaTypes {

	private AcceptedMediaTypes() {
	}

	/**
	 * Whether the request would take an HTML page as an answer.
	 * @param request the request being refused
	 * @return {@code true} when the login page is an answer to it
	 */
	static boolean acceptsHtml(ServerHttpRequest request) {
		List<MediaType> accepted = request.getHeaders().getAccept();
		// A request naming nothing takes whatever it is given, and so does one asking for
		// '*/*': curl, a link opened by hand, and the probes of the sibling instances all
		// arrive that way, and turning their redirect into a 401 would change what the
		// console has always answered them.
		return accepted.isEmpty() || accepted.stream().anyMatch(MediaType.TEXT_HTML::isCompatibleWith);
	}

}
