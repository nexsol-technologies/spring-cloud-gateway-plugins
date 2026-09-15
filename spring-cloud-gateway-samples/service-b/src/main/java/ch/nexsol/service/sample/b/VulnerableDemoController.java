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

package ch.nexsol.service.sample.b;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

/**
 * Deliberately insecure endpoints, one per issue the passive-scan plugin can raise. Route
 * this service through the gateway and hit these to see each finding light up in the
 * console: for example, through the {@code gateway-full} sample,
 * {@code curl http://localhost:8181/service-b/vulnerable/cors}.
 * <p>
 * This is a demonstration target only. Nothing here is a pattern to copy &mdash; it is
 * the catalogue of what not to do.
 */
@RestController
@RequestMapping("/vulnerable")
public class VulnerableDemoController {

	/**
	 * Lists the demo endpoints and the finding each one triggers.
	 * @return the catalogue
	 */
	@GetMapping
	public Map<String, String> index() {
		return Map.ofEntries(Map.entry("security-headers", "GET /vulnerable/security-headers"),
				Map.entry("cors", "GET /vulnerable/cors"),
				Map.entry("cookie-security", "GET /vulnerable/insecure-cookie"),
				Map.entry("info-disclosure", "GET /vulnerable/info-disclosure"),
				Map.entry("verbose-error", "GET /vulnerable/verbose-error (needs body capture)"),
				Map.entry("excessive-data-exposure", "GET /vulnerable/excessive-data (needs body capture)"),
				Map.entry("sensitive-query", "GET /vulnerable/echo?token=secret"),
				Map.entry("parameter-pollution", "GET /vulnerable/echo?id=1&id=2"),
				Map.entry("jwt-weakness", "GET /vulnerable/echo with a weak Bearer token"));
	}

	/**
	 * Answers with none of the browser-protection headers, tripping
	 * {@code security-headers}.
	 * @return a plain body
	 */
	@GetMapping("/security-headers")
	public String securityHeaders() {
		return "no security headers here";
	}

	/**
	 * Reflects any origin while allowing credentials, tripping {@code cors}.
	 * @param response the response the CORS headers are written on
	 * @return a plain body
	 */
	@GetMapping("/cors")
	public String cors(ServerHttpResponse response) {
		response.getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
		response.getHeaders().add(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
		return "permissive cors";
	}

	/**
	 * Sets a cookie with no {@code HttpOnly}, {@code Secure} or {@code SameSite},
	 * tripping {@code cookie-security}.
	 * @param response the response the cookie is written on
	 * @return a plain body
	 */
	@GetMapping("/insecure-cookie")
	public String insecureCookie(ServerHttpResponse response) {
		response.addCookie(ResponseCookie.from("SESSION", "1a2b3c").build());
		return "insecure cookie set";
	}

	/**
	 * Advertises its technology and version, tripping {@code info-disclosure}.
	 * @param response the response the headers are written on
	 * @return a plain body
	 */
	@GetMapping("/info-disclosure")
	public String infoDisclosure(ServerHttpResponse response) {
		response.getHeaders().add("Server", "Apache/2.4.1 (Unix)");
		response.getHeaders().add("X-Powered-By", "PHP/8.1.2");
		return "technology disclosed";
	}

	/**
	 * Leaks a stack trace in the body, tripping {@code verbose-error} when body capture
	 * is on.
	 * @param response the response the status is written on
	 * @return a body that looks like a leaked stack trace
	 */
	@GetMapping("/verbose-error")
	public String verboseError(ServerHttpResponse response) {
		response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
		return "java.lang.NullPointerException: boom\n\tat com.example.OrderService.load(OrderService.java:42)\n"
				+ "\tat org.springframework.web.reactive.DispatcherHandler.handle(DispatcherHandler.java:1)";
	}

	/**
	 * Returns secrets and personal data in bulk, tripping {@code excessive-data-exposure}
	 * when body capture is on.
	 * @param exchange the current exchange, used to set the JSON content type
	 * @return a body carrying data that should never leave a service
	 */
	@GetMapping("/excessive-data")
	public String excessiveData(ServerWebExchange exchange) {
		exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
		return "{\"users\":[{\"email\":\"alice@example.com\",\"password\":\"hunter2\"},"
				+ "{\"email\":\"bob@example.com\"},{\"email\":\"carol@example.com\"}],"
				+ "\"apiKey\":\"-----BEGIN RSA PRIVATE KEY-----\\nMIIEvAIB...\"}";
	}

	/**
	 * Echoes the query parameters, so a call carrying a secret parameter trips
	 * {@code sensitive-query} and a repeated parameter trips {@code parameter-pollution}.
	 * @param params the query parameters
	 * @return the echoed parameters
	 */
	@GetMapping("/echo")
	public Map<String, List<String>> echo(@RequestParam MultiValueMap<String, String> params) {
		return params;
	}

}
