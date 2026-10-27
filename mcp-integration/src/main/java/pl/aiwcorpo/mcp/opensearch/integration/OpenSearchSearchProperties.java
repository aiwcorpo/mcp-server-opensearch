package pl.aiwcorpo.mcp.opensearch.integration;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the server builds searches and which fields leave the server. The model chooses none of this.
 *
 * @param timestampField field of the time range and of the sort, e.g. {@code @timestamp}
 * @param queryFields    fields {@code simple_query_string} searches; the query text cannot name other fields
 * @param resultFields   the only {@code _source} fields returned by {@code search_logs} and {@code get_document}
 * @param maxRange       longest allowed {@code to - from}
 */
@ConfigurationProperties("opensearch.search")
public record OpenSearchSearchProperties(String timestampField, List<String> queryFields, List<String> resultFields,
        Duration maxRange) {

    /** Plain field paths: no wildcards, so the configuration cannot widen the result to every field. */
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9@_][A-Za-z0-9@_.-]{0,127}");

    public OpenSearchSearchProperties {
        timestampField = timestampField == null || timestampField.isBlank() ? "@timestamp" : timestampField;
        queryFields = queryFields == null || queryFields.isEmpty() ? List.of("message") : List.copyOf(queryFields);
        resultFields = resultFields == null || resultFields.isEmpty()
                ? List.of(timestampField, "message") : List.copyOf(resultFields);
        maxRange = maxRange == null ? Duration.ofDays(7) : maxRange;
        requireFields("timestamp-field", List.of(timestampField));
        requireFields("query-fields", queryFields);
        requireFields("result-fields", resultFields);
        if (maxRange.isNegative() || maxRange.isZero()) {
            throw new IllegalStateException("opensearch.search.max-range must be positive");
        }
    }

    private static void requireFields(String property, List<String> fields) {
        for (String field : fields) {
            if (field == null || !FIELD.matcher(field).matches()) {
                throw new IllegalStateException("opensearch.search." + property
                        + " must list plain field names (letters, digits, @ _ . -), no wildcards");
            }
        }
    }
}
