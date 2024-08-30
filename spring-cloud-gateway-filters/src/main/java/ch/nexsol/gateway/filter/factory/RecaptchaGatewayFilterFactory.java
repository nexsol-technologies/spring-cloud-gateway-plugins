/*
 * Copyright 2024 the original author or authors.
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

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import static ch.nexsol.gateway.filter.common.Constants.HTTPS_SCHEME;
import static ch.nexsol.gateway.filter.common.Constants.HTTP_SCHEME;

public class RecaptchaGatewayFilterFactory extends AbstractGatewayFilterFactory<RecaptchaGatewayFilterFactory.Config> {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RecaptchaGatewayFilterFactory.class);

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

	public RecaptchaGatewayFilterFactory(WebClient webClient) {
		super(Config.class);
		this.webClient = webClient;
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(VERIFY_URL_KEY, VERSION_KEY, SECRECT_KEY_KEY, RECAPTCHA_HTTP_HEADER_KEY, SCORE_KEY);
	}

	@Override
	public GatewayFilter apply(Config config) {
		return (exchange, chain) -> {
			ServerHttpRequest request = exchange.getRequest();
			String scheme = request.getURI().getScheme();
			if ((!HTTP_SCHEME.equalsIgnoreCase(scheme) && !HTTPS_SCHEME.equals(scheme))) {
				return chain.filter(exchange);
			}

			return extractRecaptchaToken(request, config)
				.flatMap((recaptcha) -> this
					.callRecaptchaValidateToken(config.getVerifyUrl(), config.getSecretKey(), recaptcha,
							config.getRecaptchaResponseVersion())
					.flatMap((result) -> Mono.just(result)
						.filter((r) -> r instanceof RecaptchaResponseV3)
						.cast(RecaptchaResponseV3.class)
						.flatMap((resultV3) -> validateV3(resultV3, config.getScore()))
						.map((__) -> result)
						.cast(RecaptchaResponseIdentifier.class)
						.switchIfEmpty(Mono.just(result)
							.filter((r) -> r instanceof RecaptchaResponseV2)
							.cast(RecaptchaResponseV2.class)
							.flatMap((resultV2) -> validateV2(resultV2))
							.map((__) -> result)
							.cast(RecaptchaResponseIdentifier.class))))
				.map((recaptchaResponse) -> {
					return exchange;
				})
				.defaultIfEmpty(exchange)
				.flatMap(chain::filter);

		};
	}

	private Mono<String> extractRecaptchaToken(ServerHttpRequest request, Config config) {
		HttpHeaders httpHeaders = request.getHeaders();
		if (httpHeaders.containsKey(config.getRecaptchaHttpHeader())) {
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
			.onStatus(HttpStatusCode::is4xxClientError, (response) -> response.bodyToMono(String.class).map((m) -> {
				LOG.error("Error when call {} : {} - {}", verifyUrl, response.statusCode(), m);
				throw new ResponseStatusException(response.statusCode());
			}))
			.onStatus(HttpStatusCode::is5xxServerError, (response) -> response.bodyToMono(String.class).map((m) -> {
				LOG.error("Error when call {} : {} - {}", verifyUrl, response.statusCode(), m);
				throw new ResponseStatusException(response.statusCode());
			}))
			.bodyToMono(responseVersionType);
	}

	private Mono<RecaptchaResponseV2> validateV2(RecaptchaResponseV2 recaptchaResponse) {
		if (!recaptchaResponse.isSuccess()) {
			LOG.debug("Invalid reCAPTCHA token {}", recaptchaResponse);
			return Mono.error(new BadCredentialsException("Invalid reCaptcha token"));
		}
		return Mono.just(recaptchaResponse);
	}

	private Mono<RecaptchaResponseV3> validateV3(RecaptchaResponseV3 recaptchaResponse, short V3Threshold) {
		if (!recaptchaResponse.isSuccess()) {
			LOG.debug("Invalid reCAPTCHA token {}", recaptchaResponse);
			return Mono.error(new BadCredentialsException("Invalid reCaptcha token"));
		}
		if (recaptchaResponse.getScore() * 100 < V3Threshold) {
			LOG.debug("Invalid score {} reCAPTCHA token {}", V3Threshold, recaptchaResponse);
			return Mono.error(new BadCredentialsException("Invalid reCaptcha token"));
		}
		return Mono.just(recaptchaResponse);
	}

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

		@Min(0)
		@Max(100)
		private short score;

		Class<?> getRecaptchaResponseVersion() {
			return (this.getVersion() == Version.V3) ? RecaptchaResponseV3.class : RecaptchaResponseV2.class;
		}

		public String getVerifyUrl() {
			return this.verifyUrl;
		}

		public void setVerifyUrl(String verifyUrl) {
			this.verifyUrl = verifyUrl;
		}

		public Version getVersion() {
			return this.version;
		}

		public void setVersion(Version version) {
			this.version = version;
		}

		public String getSecretKey() {
			return this.secretKey;
		}

		public void setSecretKey(String secretKey) {
			this.secretKey = secretKey;
		}

		public short getScore() {
			return this.score;
		}

		public void setScore(short score) {
			this.score = score;
		}

		public String getRecaptchaHttpHeader() {
			return this.recaptchaHttpHeader;
		}

		public void setRecaptchaHttpHeader(String recaptchaHttpHeader) {
			this.recaptchaHttpHeader = recaptchaHttpHeader;
		}

	}

	public enum Version {

		V2, V3

	}

	protected interface RecaptchaResponseIdentifier {

		String getHostname();

	}

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

		public boolean isSuccess() {
			return this.success;
		}

		public void setSuccess(boolean success) {
			this.success = success;
		}

		public String getChallengeTs() {
			return this.challengeTs;
		}

		public void setChallengeTs(String challengeTs) {
			this.challengeTs = challengeTs;
		}

		@Override
		public String getHostname() {
			return this.hostname;
		}

		public void setHostname(String hostname) {
			this.hostname = hostname;
		}

		public List<String> getErrorCodes() {
			return this.errorCodes;
		}

		public void setErrorCodes(List<String> errorCodes) {
			this.errorCodes = errorCodes;
		}

	}

	public static class RecaptchaResponseV3 extends RecaptchaResponseV2 {

		/**
		 * the score for this request (0.0 - 1.0)
		 */
		private double score;

		/**
		 * the action name for this request (important to verify)
		 */
		private String action;

		public double getScore() {
			return this.score;
		}

		public void setScore(double score) {
			this.score = score;
		}

		public String getAction() {
			return this.action;
		}

		public void setAction(String action) {
			this.action = action;
		}

	}

}
