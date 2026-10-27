package pl.aiwcorpo.mcp.opensearch.core.domain;

import java.time.Duration;

/** Limits of {@code search_logs} that come from the configuration. @param maxRange longest allowed {@code to - from} */
public record SearchLimits(Duration maxRange) {
}
