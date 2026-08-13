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

package ch.nexsol.gateway.validation.factory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ch.nexsol.gateway.validation.OpenapiContract;
import ch.nexsol.gateway.validation.OpenapiContract.Resolution;
import ch.nexsol.gateway.validation.OpenapiContractRegistry;
import ch.nexsol.gateway.validation.OpenapiRequestValidator;
import ch.nexsol.gateway.validation.OpenapiResponseValidator;
import ch.nexsol.gateway.validation.OpenapiValidationProperties;
import ch.nexsol.gateway.validation.OpenapiValidationProperties.Direction;
import ch.nexsol.gateway.validation.ValidationAttributes;
import ch.nexsol.gateway.validation.ValidationMediaTypes;
import ch.nexsol.gateway.validation.ValidationMetrics;
import ch.nexsol.gateway.validation.ValidationMetrics.SkipReason;
import ch.nexsol.gateway.validation.ValidationReport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotEmpty;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * Gateway filter factory validating the requests and the responses of a route against an
 * OpenAPI contract. Reference it in a route with the {@code OpenapiValidation} filter
 * name, giving it the location of the contract.
 * <p>
 * What happens to a message that breaks its contract is configured globally, per
 * direction, through {@link OpenapiValidationProperties}: a request is rejected with
 * {@code 400 Bad Request} and a response replaced by {@code 502 Bad Gateway} in
 * {@code ENFORCE} mode, while {@code REPORT} mode forwards both and only records the
 * violations. Either way the outcome is stamped on the exchange for the auditing plugin
 * and counted in {@link ValidationMetrics}.
 * <p>
 * A body is only ever buffered when there is something to gain from it: it has to be
 * JSON, uncompressed, and announce a length within the configured maximum. That is what
 * keeps a file upload streaming straight through &mdash; a {@code multipart/form-data} or
 * {@code application/octet-stream} payload carries nothing a JSON schema applies to, so
 * it is never read into memory, however large it is. A chunked body announcing no length
 * is left alone for the same reason: buffering it could not be bounded. Each of these is
 * counted, so what is not being validated stays visible.
 */
public class OpenapiValidationGatewayFilterFactory
		extends AbstractGatewayFilterFactory<OpenapiValidationGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(OpenapiValidationGatewayFilterFactory.class);

	private static final byte[] NO_BODY = {};

	private static final String REQUEST = "request";

	private static final String RESPONSE = "response";

	private static final String UNKNOWN_ROUTE = "unknown";

	private static final String PROBLEM_MESSAGE = "The response of the upstream service does not honour its OpenAPI contract";

	private static final byte[] PROBLEM_FALLBACK = ("{\"error\":\"" + PROBLEM_MESSAGE + "\"}")
		.getBytes(StandardCharsets.UTF_8);

	/**
	 * Configuration key holding the location of the contract.
	 */
	public static final String SPEC_URL_KEY = "specUrl";

	/**
	 * Configuration key holding the gateway side path prefix to strip.
	 */
	public static final String PATH_PREFIX_KEY = "pathPrefix";

	private final OpenapiContractRegistry registry;

	private final OpenapiValidationProperties properties;

	private final ValidationMetrics metrics;

	private final OpenapiRequestValidator requestValidator = new OpenapiRequestValidator();

	private final OpenapiResponseValidator responseValidator = new OpenapiResponseValidator();

	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Creates the factory.
	 * @param registry the registry the contracts are read through
	 * @param properties the per direction validation settings
	 * @param metrics the counters the outcomes are published to
	 */
	public OpenapiValidationGatewayFilterFactory(OpenapiContractRegistry registry,
			OpenapiValidationProperties properties, ValidationMetrics metrics) {
		super(Config.class);
		this.registry = registry;
		this.properties = properties;
		this.metrics = metrics;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Allows the filter to be configured as {@code OpenapiValidation=<specUrl>} or
	 * {@code OpenapiValidation=<specUrl>,<pathPrefix>}.
	 */
	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(SPEC_URL_KEY, PATH_PREFIX_KEY);
	}

	/**
	 * Builds the filter validating the exchange against the configured contract.
	 * @param config the filter configuration holding the contract location
	 * @return the gateway filter
	 */
	@Override
	public GatewayFilter apply(Config config) {
		// The error handling is confined to reading the contract, on purpose. Wrapping
		// the
		// validation itself would swallow the very rejection ENFORCE mode exists to
		// raise,
		// and nothing would ever be denied.
		//
		// The contract is carried in an Optional rather than signalled by an empty mono:
		// a
		// filter returns Mono<Void>, which always completes empty, so a switchIfEmpty
		// here
		// would fire on every single exchange and forward it a second time.
		return (exchange, chain) -> contractOrEmpty(config, exchange)
			.flatMap((contract) -> contract.map((present) -> filter(present, config, exchange, chain))
				.orElseGet(() -> chain.filter(exchange)));
	}

	/**
	 * Reads the contract, or returns an empty optional when it cannot be read. A contract
	 * that cannot be read is a gateway misconfiguration rather than a defect of the
	 * traffic, so the exchange is forwarded unvalidated instead of being turned into an
	 * outage &mdash; and the failure is logged, audited and counted so it cannot go
	 * unnoticed.
	 */
	private Mono<Optional<OpenapiContract>> contractOrEmpty(Config config, ServerWebExchange exchange) {
		return this.registry.contract(config.getSpecUrl()).map(Optional::of).onErrorResume(Throwable.class, (ex) -> {
			LOG.error("Forwarding without validating: the OpenAPI contract '{}' could not be read", config.getSpecUrl(),
					ex);
			this.metrics.contractUnavailable(config.getSpecUrl());
			Map<String, String> attributes = attributes(exchange);
			attributes.put(ValidationAttributes.REQUEST_VALID, Boolean.FALSE.toString());
			attributes.put(ValidationAttributes.REQUEST_ERRORS,
					"the contract '" + config.getSpecUrl() + "' could not be read: " + ex.getMessage());
			return Mono.just(Optional.empty());
		});
	}

	private Mono<Void> filter(OpenapiContract contract, Config config, ServerWebExchange exchange,
			GatewayFilterChain chain) {
		ServerHttpRequest request = exchange.getRequest();
		HttpMethod method = request.getMethod();
		String contractPath = stripPrefix(request.getPath().value(), config.getPathPrefix());
		Resolution resolution = contract.resolve(contractPath, method);
		if (!(resolution instanceof Resolution.Found found)) {
			return unresolved(resolution, contractPath, method, exchange, chain);
		}
		String operationKey = method.name() + " " + found.template();
		attributes(exchange).put(ValidationAttributes.OPERATION, operationKey);

		Direction direction = this.properties.getRequest();
		if (!direction.isActive()) {
			return proceed(exchange, chain, contract, operationKey, found);
		}
		ValidationReport parameters = this.requestValidator.validateParameters(contract, operationKey, found, request);
		HttpHeaders headers = request.getHeaders();
		if (!hasBody(headers)) {
			// No payload at all: still held against the contract, which may require one.
			ValidationReport report = parameters.and(this.requestValidator.validateBody(contract, operationKey, found,
					headers.getContentType(), NO_BODY));
			return afterRequest(report, exchange, chain, contract, operationKey, found);
		}
		SkipReason skip = (direction.isValidateBody()) ? bodySkipReason(headers, direction) : SkipReason.NOT_JSON;
		if (skip != null) {
			logSkip(REQUEST, skip, headers);
			this.metrics.bodySkipped(REQUEST, routeId(exchange), skip);
			return afterRequest(parameters, exchange, chain, contract, operationKey, found);
		}
		return ServerWebExchangeUtils.cacheRequestBodyAndRequest(exchange,
				// Bounded by the same maximum the skip decision used: that decision
				// trusts
				// the announced Content-Length, and a sender that under-announces it
				// would
				// otherwise be buffered without any limit.
				(cachedRequest) -> DataBufferUtils.join(cachedRequest.getBody(), maxBufferedBytes(direction))
					// The body announced a length within the maximum and then exceeded
					// it.
					// Its buffers are gone, so it cannot be forwarded either: say what
					// happened rather than answer the 500 an unmapped exception produces.
					.onErrorMap(DataBufferLimitException.class::isInstance,
							(ex) -> new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
									"the request body exceeds the " + direction.getMaxBodySize()
											+ " the gateway validates against"))
					.map(OpenapiValidationGatewayFilterFactory::readAndRelease)
					.defaultIfEmpty(NO_BODY)
					.flatMap((body) -> {
						ValidationReport report = parameters.and(this.requestValidator.validateBody(contract,
								operationKey, found, headers.getContentType(), body));
						ServerWebExchange mutated = exchange.mutate().request(cachedRequest).build();
						return afterRequest(report, mutated, chain, contract, operationKey, found);
					}));
	}

	private Mono<Void> afterRequest(ValidationReport report, ServerWebExchange exchange, GatewayFilterChain chain,
			OpenapiContract contract, String operationKey, Resolution.Found found) {
		Direction direction = this.properties.getRequest();
		record(exchange, ValidationAttributes.REQUEST_VALID, ValidationAttributes.REQUEST_ERRORS, report);
		this.metrics.validated(REQUEST, routeId(exchange), operationKey, direction.getMode(), report.isValid());
		if (report.isValid()) {
			return proceed(exchange, chain, contract, operationKey, found);
		}
		if (direction.isEnforced()) {
			return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, report.describe()));
		}
		LOG.warn("Request does not honour the contract for {}: {}", operationKey, report.describe());
		return proceed(exchange, chain, contract, operationKey, found);
	}

	/**
	 * Forwards the exchange, decorating the response so it is validated on its way back
	 * when the response direction asks for it.
	 */
	private Mono<Void> proceed(ServerWebExchange exchange, GatewayFilterChain chain, OpenapiContract contract,
			String operationKey, Resolution.Found found) {
		Direction direction = this.properties.getResponse();
		if (!direction.isActive()) {
			return chain.filter(exchange);
		}
		ServerHttpResponse decorated = decorate(exchange, contract, operationKey, found, direction);
		return chain.filter(exchange.mutate().response(decorated).build());
	}

	private Mono<Void> unresolved(Resolution resolution, String contractPath, HttpMethod method,
			ServerWebExchange exchange, GatewayFilterChain chain) {
		ValidationReport report = switch (resolution) {
			case Resolution.MethodNotAllowed notAllowed -> ValidationReport.of("the contract declares no " + method
					+ " operation on '" + notAllowed.template() + "', only " + notAllowed.allowedMethods());
			default -> ValidationReport.of("the contract declares no operation matching '" + contractPath + "'");
		};
		Direction direction = this.properties.getRequest();
		if (!direction.isActive()) {
			// Nothing is validated, so nothing is recorded: stamping a verdict here would
			// have the audit trail report requests as invalid on a gateway that was asked
			// not to validate them.
			return chain.filter(exchange);
		}
		record(exchange, ValidationAttributes.REQUEST_VALID, ValidationAttributes.REQUEST_ERRORS, report);
		this.metrics.validated(REQUEST, routeId(exchange), "unresolved", direction.getMode(), false);
		if (direction.isEnforced()) {
			return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, report.describe()));
		}
		LOG.warn("Request does not honour the contract: {}", report.describe());
		// Nothing was resolved, so there is no operation to hold the response against.
		return chain.filter(exchange);
	}

	private ServerHttpResponse decorate(ServerWebExchange exchange, OpenapiContract contract, String operationKey,
			Resolution.Found found, Direction direction) {
		ServerHttpResponse delegate = exchange.getResponse();
		return new ServerHttpResponseDecorator(delegate) {

			@Override
			public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
				HttpStatusCode statusCode = getStatusCode();
				if (statusCode == null) {
					return super.writeWith(body);
				}
				int status = statusCode.value();
				HttpHeaders headers = getHeaders();
				SkipReason skip = (direction.isValidateBody() && hasBody(headers)) ? bodySkipReason(headers, direction)
						: null;
				if (!direction.isValidateBody() || !hasBody(headers) || skip != null) {
					if (skip != null) {
						logSkip(RESPONSE, skip, headers);
						OpenapiValidationGatewayFilterFactory.this.metrics.bodySkipped(RESPONSE, routeId(exchange),
								skip);
					}
					ValidationReport report = OpenapiValidationGatewayFilterFactory.this.responseValidator
						.validate(contract, operationKey, found, status, headers, null);
					return handle(report, body);
				}
				// Bounded for the same reason the request body is: the skip decision
				// above
				// trusts the Content-Length the upstream announced.
				return DataBufferUtils.join(Flux.from(body), maxBufferedBytes(direction))
					.map(OpenapiValidationGatewayFilterFactory::readAndRelease)
					.defaultIfEmpty(NO_BODY)
					.flatMap((bytes) -> {
						ValidationReport report = OpenapiValidationGatewayFilterFactory.this.responseValidator
							.validate(contract, operationKey, found, status, headers, bytes);
						return handle(report, Mono.just(bufferFactory().wrap(bytes)));
					});
			}

			@Override
			public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
				return writeWith(Flux.from(body).flatMapSequential((buffers) -> buffers));
			}

			private Mono<Void> handle(ValidationReport report, Publisher<? extends DataBuffer> body) {
				record(exchange, ValidationAttributes.RESPONSE_VALID, ValidationAttributes.RESPONSE_ERRORS, report);
				OpenapiValidationGatewayFilterFactory.this.metrics.validated(RESPONSE, routeId(exchange), operationKey,
						direction.getMode(), report.isValid());
				if (report.isValid()) {
					return super.writeWith(body);
				}
				if (!direction.isEnforced()) {
					LOG.warn("Response does not honour the contract for {}: {}", operationKey, report.describe());
					return super.writeWith(body);
				}
				LOG.error("Replacing a response that does not honour the contract for {}: {}", operationKey,
						report.describe());
				// Nothing has reached the client yet, so the status and the body can
				// still be
				// replaced wholesale.
				byte[] payload = problem(operationKey, report);
				setStatusCode(HttpStatus.BAD_GATEWAY);
				HttpHeaders headers = getHeaders();
				headers.setContentType(MediaType.APPLICATION_JSON);
				headers.setContentLength(payload.length);
				return super.writeWith(Mono.just(bufferFactory().wrap(payload)));
			}

		};
	}

	private byte[] problem(String operationKey, ValidationReport report) {
		ObjectNode node = this.mapper.createObjectNode();
		node.put("error", PROBLEM_MESSAGE);
		node.put("operation", operationKey);
		report.errors().forEach(node.putArray("details")::add);
		try {
			return this.mapper.writeValueAsBytes(node);
		}
		catch (JsonProcessingException ex) {
			// Rendering a tree of strings cannot realistically fail, but the client is
			// owed
			// a body either way.
			LOG.warn("Could not render the validation problem body", ex);
			return PROBLEM_FALLBACK;
		}
	}

	/**
	 * Returns whether the message carries a payload at all. A body announcing no length
	 * but a media type is a chunked one, which counts as present.
	 */
	private static boolean hasBody(HttpHeaders headers) {
		long length = headers.getContentLength();
		if (length > 0) {
			return true;
		}
		return length < 0 && headers.getContentType() != null;
	}

	/**
	 * Returns why the body of a message should not be held against a schema, or
	 * {@code null} when it should. Deciding before reading anything is what keeps an
	 * upload out of the memory of the gateway: the media type alone settles it.
	 */
	private static SkipReason bodySkipReason(HttpHeaders headers, Direction direction) {
		if (!ValidationMediaTypes.isJson(headers.getContentType())) {
			return SkipReason.NOT_JSON;
		}
		String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
		if (StringUtils.hasText(encoding) && !"identity".equalsIgnoreCase(encoding)) {
			return SkipReason.ENCODED;
		}
		long length = headers.getContentLength();
		if (length < 0) {
			return SkipReason.UNKNOWN_LENGTH;
		}
		return (length > direction.getMaxBodySize().toBytes()) ? SkipReason.TOO_LARGE : null;
	}

	/**
	 * The largest body that is buffered, as the number of bytes {@code DataBufferUtils}
	 * takes.
	 * <p>
	 * Clamped rather than cast: a maximum above 2 GiB overflows an {@code int} into a
	 * negative one, which the limit check reads as "already exceeded" and turns into a
	 * gateway that rejects every single body it was asked to validate.
	 * @param direction the direction being validated
	 * @return the limit, capped at {@link Integer#MAX_VALUE}
	 */
	private static int maxBufferedBytes(Direction direction) {
		return (int) Math.min(direction.getMaxBodySize().toBytes(), Integer.MAX_VALUE);
	}

	private static void logSkip(String direction, SkipReason reason, HttpHeaders headers) {
		if (reason == SkipReason.NOT_JSON) {
			// The common and expected case: an upload or a binary payload.
			LOG.debug("Not validating the {} body: '{}' carries no JSON schema to hold it against", direction,
					headers.getContentType());
			return;
		}
		LOG.info("Not validating the {} body: {} (Content-Type {}, Content-Length {})", direction,
				reason.name().toLowerCase(), headers.getContentType(), headers.getContentLength());
	}

	private static byte[] readAndRelease(DataBuffer buffer) {
		try {
			byte[] bytes = new byte[buffer.readableByteCount()];
			buffer.read(bytes);
			return bytes;
		}
		finally {
			DataBufferUtils.release(buffer);
		}
	}

	/**
	 * Removes the gateway side prefix from the request path, so what is matched against
	 * the contract is the path the contract itself declares.
	 */
	private static String stripPrefix(String path, String prefix) {
		if (!StringUtils.hasText(prefix) || !path.startsWith(prefix)) {
			return path;
		}
		String stripped = path.substring(prefix.length());
		return stripped.startsWith("/") ? stripped : "/" + stripped;
	}

	private static String routeId(ServerWebExchange exchange) {
		Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
		if (route == null || !StringUtils.hasText(route.getId())) {
			return UNKNOWN_ROUTE;
		}
		return route.getId();
	}

	private static void record(ServerWebExchange exchange, String validKey, String errorsKey, ValidationReport report) {
		Map<String, String> attributes = attributes(exchange);
		attributes.put(validKey, String.valueOf(report.isValid()));
		if (!report.isValid()) {
			attributes.put(errorsKey, report.describe());
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> attributes(ServerWebExchange exchange) {
		return (Map<String, String>) exchange.getAttributes()
			.computeIfAbsent(ValidationAttributes.VALIDATION_ATTRIBUTES_ATTR,
					(key) -> new LinkedHashMap<String, String>());
	}

	/**
	 * Configuration for {@link OpenapiValidationGatewayFilterFactory}.
	 */
	@Validated
	public static class Config {

		@NotEmpty
		private String specUrl;

		private String pathPrefix;

		/**
		 * @return the location of the contract
		 */
		public String getSpecUrl() {
			return this.specUrl;
		}

		/**
		 * @param specUrl the location of the contract: an {@code http(s)} URL, or
		 * anything the resource loader understands such as {@code classpath:} or
		 * {@code file:}
		 * @return this config, for chaining
		 */
		public Config setSpecUrl(String specUrl) {
			this.specUrl = specUrl;
			return this;
		}

		/**
		 * @return the gateway side path prefix stripped before matching the contract
		 */
		public String getPathPrefix() {
			return this.pathPrefix;
		}

		/**
		 * @param pathPrefix the prefix the gateway exposes the contract under, stripped
		 * before a path is matched against it: with {@code /book-service}, a request to
		 * {@code /book-service/books} is validated against the {@code /books} operation
		 * @return this config, for chaining
		 */
		public Config setPathPrefix(String pathPrefix) {
			this.pathPrefix = pathPrefix;
			return this;
		}

	}

}
