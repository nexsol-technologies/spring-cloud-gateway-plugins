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

package ch.nexsol.service.sample;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.resource.NoResourceFoundException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@SpringBootApplication
public class ApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiApplication.class, args);
	}

	@RestController
	@Tag(name = "sample service A", description = "It is just a sample.")
	public static class Controller {

		@RequestMapping("/sample")
		public ResponseEntity<Void> sample() {
			return ResponseEntity.ok().build();
		}

	}

	@ControllerAdvice
	public class ConstraintViolationExceptionHandler {

		private static final Logger LOG = LoggerFactory.getLogger(ConstraintViolationExceptionHandler.class);

		public ConstraintViolationExceptionHandler() {
			LOG.info("Initialize default @ControllerAdvice");
		}

		@ExceptionHandler(WebExchangeBindException.class)
		public ResponseEntity<String> exception(ServerWebExchange exchange, WebExchangeBindException e) {
			LOG.error("WebExchangeBindException", e);
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		}

		@ExceptionHandler(ServerWebInputException.class)
		public ResponseEntity<String> exception(ServerWebExchange exchange, ServerWebInputException e) {
			LOG.error("ServerWebInputException", e);
			return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
		}

		@ExceptionHandler(NoResourceFoundException.class)
		public ResponseEntity<String> exception(ServerWebExchange exchange, NoResourceFoundException e) {
			LOG.error("NoResourceFoundException", e);
			throw e;
		}

	}

}
