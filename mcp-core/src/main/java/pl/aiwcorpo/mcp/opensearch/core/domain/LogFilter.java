package pl.aiwcorpo.mcp.opensearch.core.domain;

import org.jspecify.annotations.Nullable;

/**
 * What the model may ask of the log documents, as values and never as query syntax: the server decides which fields
 * they are matched against and how.
 *
 * @param text    words that all have to occur in one of the configured query fields; operators, quotes and
 *                wildcards in it are plain characters
 * @param level   one of the log levels, matched against the configured level field
 * @param service service name, matched exactly against the configured service field
 */
public record LogFilter(@Nullable String text, @Nullable String level, @Nullable String service) {
}
