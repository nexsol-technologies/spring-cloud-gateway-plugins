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

package ch.nexsol.gateway.sample.validation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gateway sample exercising the {@code spring-cloud-gateway-openapi-validation} plugin:
 * the {@code OpenapiValidation} filter holds the traffic of a route against the bookstore
 * contract shipped alongside it.
 * <p>
 * Requests are enforced and responses only reported on, which are the defaults. Nothing
 * else has to run for the request side to be visible: a request that breaks the contract
 * is denied before it is ever forwarded, so there is no backend to start.
 */
@SpringBootApplication
public class OpenapiValidationGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(OpenapiValidationGatewayApplication.class, args);
	}

}
