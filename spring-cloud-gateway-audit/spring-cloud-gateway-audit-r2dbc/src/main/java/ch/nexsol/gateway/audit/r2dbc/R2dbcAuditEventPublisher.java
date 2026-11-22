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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import ch.nexsol.gateway.audit.AuditBacklog;
import ch.nexsol.gateway.audit.AuditEvent;
import ch.nexsol.gateway.audit.AuditEventPublisher;
import ch.nexsol.gateway.audit.AuditEventSerializer;

import org.springframework.r2dbc.core.DatabaseClient;

/**
 * {@link AuditEventPublisher} inserting the audit event into a relational table through
 * R2DBC. The event timestamp is stored as a UTC {@link LocalDateTime} and the attributes
 * as a JSON string. Insertion is asynchronous and failures are logged without disrupting
 * the request. The events pending for a database slower than the traffic are bounded
 * &mdash; see {@link AuditBacklog}.
 */
public class R2dbcAuditEventPublisher implements AuditEventPublisher {

	/**
	 * A SQL identifier, quoted or not, optionally qualified by a schema. Quoted names are
	 * accepted because a database that folds case needs them to address a table named
	 * with capitals, and rejecting them would break a gateway that was configured with
	 * one.
	 */
	private static final Pattern TABLE_NAME = Pattern
		.compile("(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*)(\\.(\"[^\"]+\"|[A-Za-z_][A-Za-z0-9_$]*))?");

	private final DatabaseClient databaseClient;

	private final String insertSql;

	private final AuditEventSerializer serializer;

	private final AuditBacklog backlog;

	private final Duration sendTimeout;

	/**
	 * Create a new publisher, bounded by the defaults of {@link R2dbcAuditProperties}.
	 * @param databaseClient the R2DBC database client
	 * @param table the destination table (must expose {@code event_timestamp} and
	 * {@code attributes} columns)
	 * @param serializer the audit event serializer
	 */
	public R2dbcAuditEventPublisher(DatabaseClient databaseClient, String table, AuditEventSerializer serializer) {
		this(databaseClient, serializer, defaults(table));
	}

	/**
	 * Create a new publisher.
	 * @param databaseClient the R2DBC database client
	 * @param serializer the audit event serializer
	 * @param properties the destination table (must expose {@code event_timestamp} and
	 * {@code attributes} columns), and the bounds on the events pending
	 */
	public R2dbcAuditEventPublisher(DatabaseClient databaseClient, AuditEventSerializer serializer,
			R2dbcAuditProperties properties) {
		String table = properties.getTable();
		this.databaseClient = databaseClient;
		// The table name is concatenated into the statement, since a bind marker cannot
		// carry an identifier. It comes from the configuration rather than from a
		// request,
		// so this is not reachable from the traffic — but the check costs nothing and
		// keeps
		// the one place that builds SQL by hand from being able to build anything else.
		if (!TABLE_NAME.matcher(table).matches()) {
			throw new IllegalArgumentException(
					"'" + table + "' is not a valid table name; expected " + TABLE_NAME.pattern());
		}
		this.insertSql = "INSERT INTO " + table
				+ " (event_timestamp, attributes) VALUES (:eventTimestamp, :attributes)";
		this.serializer = serializer;
		this.backlog = new AuditBacklog("the table " + table, properties.getMaxPending());
		this.sendTimeout = properties.getSendTimeout();
	}

	private static R2dbcAuditProperties defaults(String table) {
		R2dbcAuditProperties properties = new R2dbcAuditProperties();
		properties.setTable(table);
		return properties;
	}

	@Override
	public void publish(AuditEvent event) {
		this.backlog.submit(() -> this.databaseClient.sql(this.insertSql)
			.bind("eventTimestamp", LocalDateTime.ofInstant(event.timestamp(), ZoneOffset.UTC))
			.bind("attributes", this.serializer.toJson(event))
			.fetch()
			.rowsUpdated(), this.sendTimeout);
	}

	/**
	 * @return the events pending, dropped and failed
	 */
	public AuditBacklog backlog() {
		return this.backlog;
	}

}
