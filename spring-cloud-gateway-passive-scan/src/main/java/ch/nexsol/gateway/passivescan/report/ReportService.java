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

package ch.nexsol.gateway.passivescan.report;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.nexsol.gateway.passivescan.model.Finding;
import ch.nexsol.gateway.passivescan.model.Severity;
import ch.nexsol.gateway.passivescan.store.FindingSummary;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;

/**
 * Renders the retained findings to a downloadable report: machine-readable JSON, SARIF
 * for a CI code-scanning pipeline, or a self-contained HTML page.
 */
public class ReportService {

	private static final MediaType SARIF = MediaType.parseMediaType("application/sarif+json");

	private final ObjectMapper objectMapper;

	public ReportService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Render the findings to the requested format.
	 * @param format the report format
	 * @param findings the findings to include
	 * @param summary the cumulative summary
	 * @return the rendered report
	 */
	public Report render(ReportFormat format, List<Finding> findings, FindingSummary summary) {
		return switch (format) {
			case JSON -> new Report("passive-scan.json", MediaType.APPLICATION_JSON, json(findings, summary));
			case SARIF -> new Report("passive-scan.sarif", SARIF, sarif(findings));
			case HTML -> new Report("passive-scan.html", MediaType.TEXT_HTML, html(findings, summary));
		};
	}

	private byte[] json(List<Finding> findings, FindingSummary summary) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("generatedAt", Instant.now().toString());
		root.put("summary", summary);
		root.put("findings", findings);
		return write(root);
	}

	private byte[] sarif(List<Finding> findings) {
		List<Map<String, Object>> results = new ArrayList<>();
		for (Finding finding : findings) {
			Map<String, Object> artifactLocation = Map.of("uri", String.valueOf(finding.path()));
			Map<String, Object> physicalLocation = Map.of("artifactLocation", artifactLocation);
			Map<String, Object> location = Map.of("physicalLocation", physicalLocation);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("ruleId", finding.scannerId());
			result.put("level", sarifLevel(finding.severity()));
			result.put("message", Map.of("text", finding.title() + ": " + finding.detail()));
			result.put("locations", List.of(location));
			results.add(result);
		}
		Map<String, Object> driver = Map.of("name", "spring-cloud-gateway-passive-scan");
		Map<String, Object> run = Map.of("tool", Map.of("driver", driver), "results", results);
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
		root.put("version", "2.1.0");
		root.put("runs", List.of(run));
		return write(root);
	}

	private byte[] html(List<Finding> findings, FindingSummary summary) {
		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Passive scan</title></head><body>");
		html.append("<h1>Passive scan findings</h1>");
		html.append("<p>Total findings: ").append(summary.total()).append("</p>");
		html.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">");
		html.append(
				"<tr><th>Severity</th><th>Category</th><th>Scanner</th><th>Method</th><th>Path</th><th>Detail</th></tr>");
		for (Finding finding : findings) {
			html.append("<tr><td>")
				.append(finding.severity())
				.append("</td><td>")
				.append(escape(finding.category().getCode()))
				.append("</td><td>")
				.append(escape(finding.scannerId()))
				.append("</td><td>")
				.append(escape(finding.method()))
				.append("</td><td>")
				.append(escape(finding.path()))
				.append("</td><td>")
				.append(escape(finding.detail()))
				.append("</td></tr>");
		}
		html.append("</table></body></html>");
		return html.toString().getBytes(StandardCharsets.UTF_8);
	}

	private String sarifLevel(Severity severity) {
		return switch (severity) {
			case CRITICAL, HIGH -> "error";
			case MEDIUM -> "warning";
			case LOW, INFO -> "note";
		};
	}

	private byte[] write(Object value) {
		try {
			return this.objectMapper.writeValueAsBytes(value);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to render report", ex);
		}
	}

	private String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

}
