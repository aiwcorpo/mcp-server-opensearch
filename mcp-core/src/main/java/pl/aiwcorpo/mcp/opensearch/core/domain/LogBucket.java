package pl.aiwcorpo.mcp.opensearch.core.domain;

import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/** One group of {@code aggregate_logs}: the value of the grouped field (written by whoever shipped the log) and its count. */
public record LogBucket(UntrustedText key, long count) {
}
