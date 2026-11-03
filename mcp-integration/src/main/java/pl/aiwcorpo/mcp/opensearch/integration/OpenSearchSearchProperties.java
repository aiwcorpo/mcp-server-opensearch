package pl.aiwcorpo.mcp.opensearch.integration;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the server builds searches and which fields leave the server. The model chooses none of this.
 *
 * @param timestampField field of the time range and of the sort, e.g. {@code @timestamp}
 * @param queryFields    fields the text of a search is matched against; the text cannot name other fields
 * @param resultFields   the only {@code _source} fields returned by {@code search_logs} and {@code get_document}
 * @param levelField     keyword field the level filter and the level grouping use
 * @param serviceField   keyword field the service filter and the service grouping use
 * @param hostField      keyword field the host grouping uses
 * @param maxRange       longest allowed {@code to - from}
 * @param callBudget   wall-clock time one {@code search_logs} call may spend reading backend pages
 * @param tiebreakerType sort type to use for the tiebreaker on indices that lack the field, {@code keyword} or {@code long}
 * @param tiebreakerField keyword or numeric field with a different value in every document, the last sort key of
 *                       {@code search_logs}; empty switches paging off. Not {@code _id}: it has no doc values and is
 *                       unique only within an index
 */
@ConfigurationProperties("opensearch.search")
public record OpenSearchSearchProperties(String timestampField, List<String> queryFields, List<String> resultFields,
        String levelField, String serviceField, String hostField, Duration maxRange, Duration callBudget, String tiebreakerType, String tiebreakerField) {

    /** Plain field paths: no wildcards, so the configuration cannot widen the result to every field. */
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9@_][A-Za-z0-9@_.-]{0,127}");

    public OpenSearchSearchProperties {
        timestampField = timestampField == null || timestampField.isBlank() ? "@timestamp" : timestampField;
        queryFields = queryFields == null || queryFields.isEmpty() ? List.of("message") : List.copyOf(queryFields);
        resultFields = resultFields == null || resultFields.isEmpty()
                ? List.of(timestampField, "message") : List.copyOf(resultFields);
        levelField = levelField == null || levelField.isBlank() ? "log.level" : levelField;
        serviceField = serviceField == null || serviceField.isBlank() ? "service.name" : serviceField;
        hostField = hostField == null || hostField.isBlank() ? "host.name" : hostField;
        maxRange = maxRange == null ? Duration.ofDays(7) : maxRange;
        callBudget = callBudget == null ? Duration.ofSeconds(20) : callBudget;
        tiebreakerType = tiebreakerType == null || tiebreakerType.isBlank() ? "keyword" : tiebreakerType.strip();
        tiebreakerField = tiebreakerField == null ? "" : tiebreakerField.strip();
        if (!tiebreakerType.equals("keyword") && !tiebreakerType.equals("long")) {
            throw new IllegalStateException("opensearch.search.tiebreaker-type must be keyword or long");
        }
        if (callBudget.isNegative() || callBudget.isZero()) {
            throw new IllegalStateException("opensearch.search.call-budget must be positive");
        }
        requireFields("timestamp-field", List.of(timestampField));
        requireFields("query-fields", queryFields);
        requireFields("result-fields", resultFields);
        requireFields("level-field", List.of(levelField));
        requireFields("service-field", List.of(serviceField));
        requireFields("host-field", List.of(hostField));
        if (!tiebreakerField.isEmpty()) {
            if (tiebreakerField.startsWith("_")) {
                throw new IllegalStateException("opensearch.search.tiebreaker-field must be a field of the documents,"
                        + " not a metadata field such as _id (no doc values, unique only within an index)");
            }
            requireFields("tiebreaker-field", List.of(tiebreakerField));
        }
        if (maxRange.isNegative() || maxRange.isZero()) {
            throw new IllegalStateException("opensearch.search.max-range must be positive");
        }
    }

    /** The fields a mapping may show: the ones this server queries, returns, groups by or sorts by. */
    public java.util.Set<String> mappingFields() {
        java.util.Set<String> fields = new java.util.LinkedHashSet<>();
        fields.add(timestampField);
        fields.addAll(queryFields);
        fields.addAll(resultFields);
        fields.add(levelField);
        fields.add(serviceField);
        fields.add(hostField);
        if (pagingEnabled()) {
            fields.add(tiebreakerField);
        }
        return fields;
    }

    public boolean pagingEnabled() {
        return !tiebreakerField.isEmpty();
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

