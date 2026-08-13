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

package ch.nexsol.gateway.database.controller.error;

import ch.nexsol.gateway.database.controller.FilterController;
import ch.nexsol.gateway.database.controller.PredicateController;
import ch.nexsol.gateway.database.controller.RouteController;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

/**
 * Controller advice translating the request validation and binding failures of the route
 * management controllers into HTTP 400 responses.
 * <p>
 * Scoped to the controllers of this plugin: an unrestricted {@code @ControllerAdvice}
 * applies to every controller of the host application, so it would quietly take over the
 * error handling of endpoints that have nothing to do with the gateway routes, and answer
 * them with the bodiless 400 this one produces.
 */
@ControllerAdvice(assignableTypes = { RouteController.class, FilterController.class, PredicateController.class })
public class ControllerExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ControllerExceptionHandler.class);

	/**
	 * Creates the default controller advice.
	 */
	public ControllerExceptionHandler() {
		LOG.info("Initialize default @ControllerAdvice");
	}

	/**
	 * Handles constraint violations by returning an HTTP 400 response.
	 * @param exchange the current server exchange
	 * @param e the constraint violation
	 * @return a 400 response
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<String> constraintViolationException(ServerWebExchange exchange,
			ConstraintViolationException e) {
		LOG.error("ConstraintViolationException", e);
		return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
	}

	/**
	 * Handles request body binding failures by returning an HTTP 400 response.
	 * @param exchange the current server exchange
	 * @param e the binding failure
	 * @return a 400 response
	 */
	@ExceptionHandler(WebExchangeBindException.class)
	public ResponseEntity<String> exception(ServerWebExchange exchange, WebExchangeBindException e) {
		LOG.error("WebExchangeBindException", e);
		return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
	}

	/**
	 * Handles malformed request input by returning an HTTP 400 response.
	 * @param exchange the current server exchange
	 * @param e the input failure
	 * @return a 400 response
	 */
	@ExceptionHandler(ServerWebInputException.class)
	public ResponseEntity<String> exception(ServerWebExchange exchange, ServerWebInputException e) {
		LOG.error("ServerWebInputException", e);
		return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
	}

}
