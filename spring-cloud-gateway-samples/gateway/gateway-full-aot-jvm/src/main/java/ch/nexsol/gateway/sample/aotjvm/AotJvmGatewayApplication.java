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

package ch.nexsol.gateway.sample.aotjvm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;

/**
 * Gateway sample carrying every plugin that contributes ahead-of-time hints, built with
 * Spring ahead-of-time processing on the JVM.
 * <p>
 * The bean definitions are generated at build time by {@code spring-boot:process-aot} and
 * used when the application is started with {@code -Dspring.aot.enabled=true}. The
 * conditions are evaluated during that build, so the profile and the properties the image
 * is built with are the ones it runs with.
 */
@SpringBootApplication
public class AotJvmGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(AotJvmGatewayApplication.class, args);
	}

	/**
	 * The one user of this sample, {@code sample} / {@code sample}, with the role the
	 * {@code authorization} route compares against.
	 * <p>
	 * Without a user details service, Spring Boot's management security answers every
	 * path but health and info with a deny-all authentication manager: the actuator's
	 * gateway endpoints, and whatever the plugins put behind the console's security
	 * chain, refuse everybody, since no other identity provider is configured here.
	 */
	@Bean
	MapReactiveUserDetailsService sampleUser() {
		return new MapReactiveUserDetailsService(
				User.withUsername("sample").password("{noop}sample").roles("READ").build());
	}

}
