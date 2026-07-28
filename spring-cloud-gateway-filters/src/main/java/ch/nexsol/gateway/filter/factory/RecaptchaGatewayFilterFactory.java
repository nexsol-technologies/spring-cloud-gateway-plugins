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

package ch.nexsol.gateway.filter.factory;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import static ch.nexsol.gateway.filter.common.Constants.HTTPS_SCHEME;
import static ch.nexsol.gateway.filter.common.Constants.HTTP_SCHEME;

/**
 * Gateway filter factory that verifies a Google reCAPTCHA token carried on the incoming
 * request against the reCAPTCHA verification endpoint, supporting both v2 and v3
 * responses, and rejects requests that fail validation.
 */
public class RecaptchaGatewayFilterFactory extends AbstractGatewayFilterFactory<RecaptchaGatewayFilterFactory.Config> {

	private static final Logger LOG = LoggerFactory.getLogger(RecaptchaGatewayFilterFactory.class);

	/**
	 * Maximum time to wait for the reCAPTCHA verification endpoint before failing the
	 * request, to avoid a slow/hung provider blocking the gateway indefinitely.
	 */
	private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(10);

	/**
	 * VerifyUrl key.
	 */
	public static final String VERIFY_URL_KEY = "verifyUrl";

	/**
	 * Version key.
	 */
	public static final String VERSION_KEY = "version";

	/**
	 * SecretKey key.
	 */
	public static final String SECRECT_KEY_KEY = "secretKey";

	/**
	 * RecaptchaHttpHeader key.
	 */
	public static final String RECAPTCHA_HTTP_HEADER_KEY = "recaptchaHttpHeader";

	/**
	 * Score key.
	 */
	public static final String SCORE_KEY = "score";

	private final WebClient webClient;

	/**
	 * Creates the factory bound to its {@link Config} type.
	 * @param webClient the web client used to call the reCAPTCHA verification endpoint
	 */
	public RecaptchaGatewayFilterFactory(WebClient webClient) {
		super(Config.class);
		this.webClient = webClient;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Maps the shortcut arguments, in order, to the verify URL, version, secret key,
	 * reCAPTCHA header name and minimum score configuration fields.
	 */
	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(VERIFY_URL_KEY, VERSION_KEY, SECRECT_KEY_KEY, RECAPTCHA_HTTP_HEADER_KEY, SCORE_KEY);
	}

	/**
	 * Builds a filter that extracts the reCAPTCHA token, verifies it against the provider
	 * and only forwards the request when validation succeeds, failing closed otherwise.
	 * @param config the reCAPTCHA verification configuration
	 * @return a gateway filter enforcing reCAPTCHA validation
	 */
	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			ServerHttpRequest request = exchange.getRequest();
			String scheme = request.getURI().getScheme();
			if ((!HTTP_SCHEME.equalsIgnoreCase(scheme) && !HTTPS_SCHEME.equalsIgnoreCase(scheme))) {
				return chain.filter(exchange);
			}

			return extractRecaptchaToken(request, config).flatMap((recaptcha) -> verify(config, recaptcha))
				// Fail closed: if validation produced no result, deny rather than let the
				// request through unverified.
				.switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
						"reCAPTCHA validation could not be completed")))
				.flatMap((recaptchaResponse) -> chain.filter(exchange));
		};
	}

	/**
	 * Verifies the token against the provider and validates the answer with the rules of
	 * the configured version.
	 * <p>
	 * The version drives the dispatch rather than the runtime type of the answer:
	 * {@link RecaptchaResponseV3} extends {@link RecaptchaResponseV2}, so an
	 * {@code instanceof} chain would only be correct as long as the v3 branch came first.
	 * @param config the reCAPTCHA verification configuration
	 * @param recaptchaToken the token carried by the request
	 * @return the validated response, or an error when it does not pass
	 */
	private Mono<RecaptchaResponseIdentifier> verify(Config config, String recaptchaToken) {
		Mono<RecaptchaResponseIdentifier> verdict = switch (config.getVersion()) {
			case V3 -> callRecaptchaValidateToken(config.getVerifyUrl(), config.getSecretKey(), recaptchaToken,
					RecaptchaResponseV3.class)
				.flatMap((response) -> validateV3(response, config.getScore()))
				.cast(RecaptchaResponseIdentifier.class);
			case V2 -> callRecaptchaValidateToken(config.getVerifyUrl(), config.getSecretKey(), recaptchaToken,
					RecaptchaResponseV2.class)
				.flatMap(this::validateV2)
				.cast(RecaptchaResponseIdentifier.class);
		};
		return verdict.onErrorResume((error) -> !isRejection(error), (error) -> {
			// No verdict could be reached: the endpoint was unreachable, timed out,
			// refused the call or answered something unreadable. Deny, like every other
			// path that cannot confirm the token, and log why — the caller is not the one
			// who can act on it.
			LOG.error("Could not verify the reCAPTCHA token against {}", config.getVerifyUrl(), error);
			return Mono.error(rejected());
		});
	}

	private boolean isRejection(Throwable error) {
		return error instanceof ResponseStatusException statusException
				&& HttpStatus.FORBIDDEN.equals(statusException.getStatusCode());
	}

	private Mono<String> extractRecaptchaToken(ServerHttpRequest request, Config config) {
		HttpHeaders httpHeaders = request.getHeaders();
		if (httpHeaders.containsHeader(config.getRecaptchaHttpHeader())) {
			String token = httpHeaders.getFirst(config.getRecaptchaHttpHeader());
			if (StringUtils.hasText(token)) {
				return Mono.just(token);
			}
		}
		return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
				"Not Authorized to access to this internal resource due to missing captcha"));
	}

	private <T> Mono<T> callRecaptchaValidateToken(String verifyUrl, String secretKey, String recaptchaToken,
			Class<T> responseVersionType) {
		MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
		map.add("secret", secretKey);
		map.add("response", recaptchaToken);
		return this.webClient.post()
			.uri(verifyUrl)
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.bodyValue(map)
			.retrieve()
			// The handler returns the error rather than throwing it, and defaults the
			// body, so an error answer with an empty body still produces one — an empty
			// bodyToMono would otherwise emit nothing and let the call carry on as if
			// the status had been fine.
			.onStatus(HttpStatusCode::is4xxClientError, (response) -> verificationFailed(verifyUrl, response))
			.onStatus(HttpStatusCode::is5xxServerError, (response) -> verificationFailed(verifyUrl, response))
			.bodyToMono(responseVersionType)
			.timeout(VERIFY_TIMEOUT);
	}

	private Mono<Throwable> verificationFailed(String verifyUrl, ClientResponse response) {
		return response.bodyToMono(String.class)
			.defaultIfEmpty("")
			.map((body) -> new IllegalStateException("The reCAPTCHA verification endpoint %s answered %s: %s"
				.formatted(verifyUrl, response.statusCode(), body)));
	}

	private Mono<RecaptchaResponseV2> validateV2(RecaptchaResponseV2 recaptchaResponse) {
		if (!recaptchaResponse.isSuccess()) {
			LOG.debug("Invalid reCAPTCHA token {}", recaptchaResponse);
			return Mono.error(rejected());
		}
		return Mono.just(recaptchaResponse);
	}

	private Mono<RecaptchaResponseV3> validateV3(RecaptchaResponseV3 recaptchaResponse, short threshold) {
		if (!recaptchaResponse.isSuccess()) {
			LOG.debug("Invalid reCAPTCHA token {}", recaptchaResponse);
			return Mono.error(rejected());
		}
		// Compared on the 0.0-1.0 scale the provider answers on, rather than by scaling
		// the score up: 0.29 * 100 is 28.999999999999996, which would reject a score
		// exactly meeting a threshold of 29.
		if (recaptchaResponse.getScore() < threshold / 100.0) {
			LOG.debug("reCAPTCHA score {} below the threshold {} for token {}", recaptchaResponse.getScore(), threshold,
					recaptchaResponse);
			return Mono.error(rejected());
		}
		return Mono.just(recaptchaResponse);
	}

	/**
	 * The verdict a failed verification is answered with. It carries a status, so the
	 * caller is told it was refused rather than getting the 500 an exception no handler
	 * maps produces &mdash; and the same status as a request carrying no token at all.
	 * @return the exception denying the request
	 */
	private ResponseStatusException rejected() {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, "reCAPTCHA validation failed");
	}

	/**
	 * Configuration for {@link RecaptchaGatewayFilterFactory}.
	 */
	@Validated
	public static class Config {

		@NotEmpty
		private String verifyUrl;

		@NotNull
		private Version version = Version.V3;

		@NotEmpty
		private String secretKey;

		@NotEmpty
		private String recaptchaHttpHeader = "recaptcha";

		/**
		 * Minimum v3 score, on a 0-100 scale, for a token to be accepted. Google
		 * recommends 0.5 as a starting point, which is what this default is: legitimate
		 * traffic commonly scores between 0.7 and 0.9, and a stricter threshold turns
		 * real users away.
		 */
		@Min(0)
		@Max(100)
		private short score = 50;

		/**
		 * Returns the reCAPTCHA verification endpoint URL.
		 * @return the verify URL
		 */
		public String getVerifyUrl() {
			return this.verifyUrl;
		}

		/**
		 * Sets the reCAPTCHA verification endpoint URL.
		 * @param verifyUrl the verify URL
		 */
		public void setVerifyUrl(String verifyUrl) {
			this.verifyUrl = verifyUrl;
		}

		/**
		 * Returns the configured reCAPTCHA version.
		 * @return the reCAPTCHA version
		 */
		public Version getVersion() {
			return this.version;
		}

		/**
		 * Sets the reCAPTCHA version to validate against.
		 * @param version the reCAPTCHA version
		 */
		public void setVersion(Version version) {
			this.version = version;
		}

		/**
		 * Returns the reCAPTCHA secret key.
		 * @return the secret key
		 */
		public String getSecretKey() {
			return this.secretKey;
		}

		/**
		 * Sets the reCAPTCHA secret key.
		 * @param secretKey the secret key
		 */
		public void setSecretKey(String secretKey) {
			this.secretKey = secretKey;
		}

		/**
		 * Returns the minimum score (0-100) required for a v3 token to be accepted.
		 * @return the minimum score threshold
		 */
		public short getScore() {
			return this.score;
		}

		/**
		 * Sets the minimum score (0-100) required for a v3 token to be accepted.
		 * @param score the minimum score threshold
		 */
		public void setScore(short score) {
			this.score = score;
		}

		/**
		 * Returns the name of the request header carrying the reCAPTCHA token.
		 * @return the reCAPTCHA header name
		 */
		public String getRecaptchaHttpHeader() {
			return this.recaptchaHttpHeader;
		}

		/**
		 * Sets the name of the request header carrying the reCAPTCHA token.
		 * @param recaptchaHttpHeader the reCAPTCHA header name
		 */
		public void setRecaptchaHttpHeader(String recaptchaHttpHeader) {
			this.recaptchaHttpHeader = recaptchaHttpHeader;
		}

	}

	/**
	 * recapatcha versions managed by the filter (And Google also).
	 */
	public enum Version {

		V2, V3

	}

	/**
	 * Common contract shared by the v2 and v3 reCAPTCHA response payloads.
	 */
	protected interface RecaptchaResponseIdentifier {

		/**
		 * Returns the hostname of the site where the reCAPTCHA was solved.
		 * @return the reCAPTCHA hostname
		 */
		String getHostname();

	}

	/**
	 * Documentation from : https://developers.google.com/recaptcha/docs/v3
	 */
	public static class RecaptchaResponseV2 implements RecaptchaResponseIdentifier {

		/**
		 * whether this request was a valid reCAPTCHA token for your site
		 */
		private boolean success;

		/**
		 * timestamp of the challenge load (ISO format yyyy-MM-dd'T'HH:mm:ssZZ)
		 */
		@JsonProperty("challenge_ts")
		private String challengeTs;

		/**
		 * the hostname of the site where the reCAPTCHA was solved
		 */
		private String hostname;

		/**
		 * optional
		 */
		@JsonProperty("error-codes")
		private List<String> errorCodes;

		/**
		 * Returns whether the token was a valid reCAPTCHA response for the site.
		 * @return {@code true} if the verification succeeded
		 */
		public boolean isSuccess() {
			return this.success;
		}

		/**
		 * Sets whether the token was a valid reCAPTCHA response for the site.
		 * @param success the success flag
		 */
		public void setSuccess(boolean success) {
			this.success = success;
		}

		/**
		 * Returns the timestamp of the challenge load in ISO format.
		 * @return the challenge timestamp
		 */
		public String getChallengeTs() {
			return this.challengeTs;
		}

		/**
		 * Sets the timestamp of the challenge load.
		 * @param challengeTs the challenge timestamp
		 */
		public void setChallengeTs(String challengeTs) {
			this.challengeTs = challengeTs;
		}

		/** {@inheritDoc} */
		@Override
		public String getHostname() {
			return this.hostname;
		}

		/**
		 * Sets the hostname of the site where the reCAPTCHA was solved.
		 * @param hostname the reCAPTCHA hostname
		 */
		public void setHostname(String hostname) {
			this.hostname = hostname;
		}

		/**
		 * Returns the optional error codes returned by the verification endpoint.
		 * @return the error codes, or {@code null} if none
		 */
		public List<String> getErrorCodes() {
			return this.errorCodes;
		}

		/**
		 * Sets the optional error codes returned by the verification endpoint.
		 * @param errorCodes the error codes
		 */
		public void setErrorCodes(List<String> errorCodes) {
			this.errorCodes = errorCodes;
		}

	}

	/**
	 * Documentation from : https://developers.google.com/recaptcha/docs/v3
	 */
	public static class RecaptchaResponseV3 extends RecaptchaResponseV2 {

		/**
		 * the score for this request (0.0 - 1.0)
		 */
		private double score;

		/**
		 * the action name for this request (important to verify)
		 */
		private String action;

		/**
		 * Returns the v3 score for this request (0.0 - 1.0).
		 * @return the reCAPTCHA score
		 */
		public double getScore() {
			return this.score;
		}

		/**
		 * Sets the v3 score for this request.
		 * @param score the reCAPTCHA score
		 */
		public void setScore(double score) {
			this.score = score;
		}

		/**
		 * Returns the action name associated with this request.
		 * @return the action name
		 */
		public String getAction() {
			return this.action;
		}

		/**
		 * Sets the action name associated with this request.
		 * @param action the action name
		 */
		public void setAction(String action) {
			this.action = action;
		}

	}

}
