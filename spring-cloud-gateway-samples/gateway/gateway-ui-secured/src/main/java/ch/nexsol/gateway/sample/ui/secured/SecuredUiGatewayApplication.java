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

package ch.nexsol.gateway.sample.ui.secured;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gateway sample putting the login page of the console in front of the shell.
 * <p>
 * It declares no security configuration of its own: the {@code ui} plugin contributes the
 * chain, and everything the sample does is set
 * {@code spring.cloud.gateway.server.webflux.ui.security.mode=authenticated} and name a
 * local user. The {@code keycloak} profile adds the identity provider on top, so the same
 * console can be signed into either way.
 */
@SpringBootApplication
public class SecuredUiGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(SecuredUiGatewayApplication.class, args);
	}

}
