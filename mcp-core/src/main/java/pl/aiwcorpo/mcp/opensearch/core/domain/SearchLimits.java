package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.time.Duration;

/**
 * Limits of the search tools that come from the configuration.
 *
 * @param maxRange     longest allowed {@code to - from}
 * @param callBudget   wall-clock time one {@code search_logs} call may spend reading backend pages before it returns what it has
 * @param messageField result field that holds the log message, the one {@code find_log_patterns} groups
 */
public record SearchLimits(Duration maxRange, Duration callBudget, String messageField) {
}
