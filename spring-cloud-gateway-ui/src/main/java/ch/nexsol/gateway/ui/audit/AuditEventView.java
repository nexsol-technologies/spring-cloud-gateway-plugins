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

package ch.nexsol.gateway.ui.audit;

import java.time.Instant;
import java.util.Map;

import ch.nexsol.gateway.audit.AuditAttributes;
import ch.nexsol.gateway.audit.AuditEvent;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * One audited exchange, flattened for the audit view: the few attributes worth a column
 * of their own, plus every audited attribute for the expandable detail.
 *
 * @param timestamp the instant the event was captured
 * @param method the request method
 * @param path the request path
 * @param status the response status as audited, e.g. {@code OK}
 * @param statusCode the numeric response status, or {@code 0} when it could not be
 * resolved
 * @param user the authenticated user id
 * @param ip the remote client ip
 * @param traceId the trace identifier
 * @param attributes every audited attribute, in the order the audit plugin collected them
 */
public record AuditEventView(Instant timestamp, String method, String path, String status, int statusCode, String user,
		String ip, String traceId, Map<String, String> attributes) {

	/**
	 * Flattens an audit event for display. Attributes the audit groups leave out are
	 * simply absent, so the view degrades to whatever the plugin was configured to
	 * collect.
	 * @param event the audit event to flatten
	 * @return the view of the event
	 */
	public static AuditEventView of(AuditEvent event) {
		Map<String, String> attributes = event.attributes();
		String status = attributes.get(AuditAttributes.RESPONSE_STATUS);
		return new AuditEventView(event.timestamp(), attributes.get(AuditAttributes.REQUEST_METHOD),
				attributes.get(AuditAttributes.REQUEST_PATH), status, statusCode(status),
				attributes.get(AuditAttributes.JWT_USER_ID), attributes.get(AuditAttributes.REQUEST_IP),
				attributes.get(AuditAttributes.TRACE_ID), attributes);
	}

	/**
	 * Resolves the numeric status behind the audited value, which the audit plugin
	 * records as the status name whenever it is a known one.
	 * @param status the audited status value
	 * @return the numeric status, or {@code 0} when it cannot be resolved
	 */
	static int statusCode(String status) {
		if (!StringUtils.hasText(status) || AuditAttributes.NONE_VALUE.equals(status)) {
			return 0;
		}
		if (status.chars().allMatch(Character::isDigit)) {
			return Integer.parseInt(status);
		}
		try {
			return HttpStatus.valueOf(status).value();
		}
		catch (IllegalArgumentException ex) {
			return 0;
		}
	}

}
