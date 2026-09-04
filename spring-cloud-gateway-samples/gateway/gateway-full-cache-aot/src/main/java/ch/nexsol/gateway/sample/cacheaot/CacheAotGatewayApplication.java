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
	@Bean
	@ConditionalOnProperty(name = "sample.training-run", havingValue = "true")
	ApplicationListener<ApplicationReadyEvent> trainingRunTerminator() {
		return (event) -> event.getApplicationContext().close();
	}

}
