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

package ch.nexsol.gateway.sample.nativeimage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gateway sample carrying every plugin that contributes ahead-of-time hints, built as a
 * GraalVM native image.
 * <p>
 * This is the build the hints exist for: a route argument bound reflectively, an OpenAPI
 * contract parsed reflectively and a reading serialized reflectively all fail here, and
 * nowhere else, when their type carries no hint.
 */
@SpringBootApplication
public class NativeGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(NativeGatewayApplication.class, args);
	}

}
