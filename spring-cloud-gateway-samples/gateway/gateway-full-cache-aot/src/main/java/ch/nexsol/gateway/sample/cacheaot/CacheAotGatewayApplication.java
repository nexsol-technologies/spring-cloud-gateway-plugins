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

package ch.nexsol.gateway.sample.cacheaot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;

/**
 * Gateway sample carrying every plugin that contributes ahead-of-time hints, built to be
 * started against a JDK class data sharing archive or an AOT cache.
 * <p>
 * Neither archive constrains the code: they record what a first run loaded and replay it.
 * What they do need is a run that ends by itself, which is what
 * {@code --sample.training-run} gives, since a gateway otherwise serves until it is
 * killed and a killed JVM writes no cache.
 */
@SpringBootApplication
public class CacheAotGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(CacheAotGatewayApplication.class, args);
	}

	/**
	 * Ends the run once the gateway is up, so a training run writes its archive instead
	 * of serving until it is killed.
	 * @return the listener closing the context on the ready event
	 */
	// Not spring.context.exit=onRefresh, which Spring ships for this: it halts before the
	// lifecycle beans start, so Netty never binds and the classes it loads on start-up
	// are left out of the cache. Closing on the ready event records them.
	@Bean
	@ConditionalOnProperty(name = "sample.training-run", havingValue = "true")
	ApplicationListener<ApplicationReadyEvent> trainingRunTerminator() {
		return (event) -> event.getApplicationContext().close();
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
