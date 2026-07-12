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

package ch.nexsol.gateway.audit.r2dbc;

/**
 * Configuration properties for the R2DBC audit provider, bound under
 * {@code spring.cloud.gateway.server.webflux.audit.r2dbc}.
 */
public class R2dbcAuditProperties {

	/**
	 * Table the audit events are inserted into. It must expose an {@code event_timestamp}
	 * and an {@code attributes} column.
	 */
	private String table = "audit_event";

	/**
	 * @return the table
	 */
	public String getTable() {
		return this.table;
	}

	/**
	 * @param table the table
	 */
	public void setTable(String table) {
		this.table = table;
	}

}
