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

package ch.nexsol.gateway.metrics.prometheus;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The part of a Prometheus {@code /api/v1/query} answer this source reads: an instant
 * vector, each sample carrying its labels and a single value.
 *
 * @param status {@code success} when the query was answered
 * @param data the returned vector
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrometheusQueryResponse(String status, Data data) {

	/**
	 * The result vector.
	 *
	 * @param result the samples
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Data(List<Sample> result) {
	}

	/**
	 * A single sample.
	 *
	 * @param metric the labels of the series
	 * @param value the timestamp and the value, the latter rendered as a string by
	 * Prometheus
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Sample(Map<String, String> metric, List<Object> value) {

		/**
		 * @return the label of the given name, {@code null} when the series does not
		 * carry it
		 */
		public String label(String name) {
			return (this.metric != null) ? this.metric.get(name) : null;
		}

		/**
		 * Returns the sample value. Prometheus renders it as a string, and can return
		 * {@code NaN} or {@code +Inf} for a series with no data.
		 * @return the value, {@code 0} when it is missing or not a finite number
		 */
		public double doubleValue() {
			if (this.value == null || this.value.size() < 2 || this.value.get(1) == null) {
				return 0.0;
			}
			try {
				double parsed = Double.parseDouble(this.value.get(1).toString());
				return Double.isFinite(parsed) ? parsed : 0.0;
			}
			catch (NumberFormatException ex) {
				return 0.0;
			}
		}

	}

}
