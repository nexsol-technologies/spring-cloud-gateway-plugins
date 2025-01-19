package ch.nexsol.service.sample;

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
