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

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The service at the far end of both flows of the service graph sample: {@code service-a}
 * calls it directly on this port, and again through the gateway.
 * <p>
 * It answers with the headers that named its caller, so a reader can see which of the two
 * flows a response came from without reading the gateway logs.
 */
@SpringBootApplication
public class ServiceBApplication {

	public static void main(String[] args) {
		SpringApplication.run(ServiceBApplication.class, args);
	}

	/**
	 * The one endpoint {@code service-a} calls.
	 */
	@RestController
	public static class Controller {

		/**
		 * Answers with what the request said about its caller.
		 * @param caller the caller header the calling service set, absent when nothing
		 * named it
		 * @return what service-b saw of its caller
		 */
		@GetMapping("/data")
		public Map<String, String> data(@RequestHeader(name = "X-Caller", required = false) String caller) {
			return Map.of("service", "service-b", "calledBy", (caller != null) ? caller : "unknown");
		}

	}

}
