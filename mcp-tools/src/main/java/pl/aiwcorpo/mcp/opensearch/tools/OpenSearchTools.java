package pl.aiwcorpo.mcp.opensearch.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexPattern;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchLimits;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpenSearchUseCase;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP tools of this server. Every argument is untrusted input from a model. Parameter names are the public
 * argument names of the input schema, hence {@code index_pattern}; the limits in {@link ArgumentLimits} are
 * published in the schema by {@code SchemaLimitedToolCallbacks} and enforced by the starter before the method
 * runs, and the checks below repeat what the tool relies on.
 *
 * <p>Index arguments are checked here for syntax and fail closed on everything OpenSearch would read as more
 * than one plain name: lists, exclusions, date math, remote clusters, {@code _all}, a bare {@code *} and
 * hidden {@code .} indices. Prefix and alias checks follow in {@code OpenSearchService}.
 */
@Service
public class OpenSearchTools {

    public static final String SEARCH_LOGS = "search_logs";
    public static final String GET_DOCUMENT = "get_document";

    /** Pattern of the brief and of the input schema; a leading '-' (exclusion) is rejected separately. */
    private static final String INDEX_PATTERN_REGEX = "^[a-z0-9-]+\\*?$";
    /** Document ids that fit a URL path segment; the same text goes to the input schema. */
    private static final String DOCUMENT_ID_REGEX = "^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$";
    private static final Pattern INDEX_PATTERN = Pattern.compile(INDEX_PATTERN_REGEX);
    /**
     * {@code OpenSearchIndexName} read from the contract on the classpath, the same pattern the input schema
     * publishes. Since contracts 2.1 (CC-074) it excludes a leading dot itself.
     */
    private static final Pattern INDEX_NAME = contractPattern("OpenSearchIndexName");
    /** OpenSearch ids are free text up to 512 bytes; this keeps ids that fit a URL path segment unescaped. */
    private static final Pattern DOCUMENT_ID = Pattern.compile(DOCUMENT_ID_REGEX);
    private static final int MAX_QUERY = 512;
    private static final int MAX_TIME = 40;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private final OpenSearchUseCase useCase;
    private final Duration maxRange;

    public OpenSearchTools(OpenSearchUseCase useCase, SearchLimits limits) {
        this.useCase = useCase;
        this.maxRange = limits.maxRange();
    }

    @Tool(name = SEARCH_LOGS, description = "Searches log documents in the indices matching one"
            + " index pattern within a time range. Returns up to size entries, newest first, each with index, id,"
            + " timestamp and the configured log fields under 'data'. No aggregations and no total count.")
    @ToolRisk(RiskLevel.READ_ONLY)
    public SearchLogsResult searchLogs(
            @ToolParam(description = "One index name, or one name followed by '*'. Lower-case letters, digits and '-'")
            @ArgumentLimits(minLength = 1, maxLength = 255, pattern = INDEX_PATTERN_REGEX) String index_pattern,
            @ToolParam(description = "Text searched in the configured log fields (words, \"phrases\", +, |, -, trailing *)")
            @ArgumentLimits(minLength = 1, maxLength = MAX_QUERY) String query,
            @ToolParam(description = "Start of the time range, inclusive, ISO-8601 with offset, e.g. 2026-10-27T08:00:00Z")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String from,
            @ToolParam(description = "End of the time range, exclusive, ISO-8601 with offset, later than from")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String to,
            @ToolParam(description = "Number of entries, 1-100, default 20", required = false)
            @ArgumentLimits(minimum = 1, maximum = MAX_SIZE) Integer size) {
        IndexPattern pattern = validPattern(index_pattern);
        String text = validQuery(query);
        Instant start = validTime("from", from);
        Instant end = validTime("to", to);
        if (!end.isAfter(start)) {
            throw new InvalidToolArgumentException("to must be later than from");
        }
        if (Duration.between(start, end).compareTo(maxRange) > 0) {
            throw new InvalidToolArgumentException("the time range from-to may not exceed " + maxRange.toHours() + " hours");
        }
        int count = validSize(size);
        List<LogHit> hits = useCase.searchLogs(new LogQuery(pattern, text, start, end, count));
        return SearchLogsResult.of(pattern.toString(), start.toString(), end.toString(), hits);
    }

    @Tool(name = GET_DOCUMENT, description = "Returns one document by index and id: index, id"
            + " and the configured fields under 'data'.")
    @ToolRisk(RiskLevel.READ_ONLY)
    public DocumentResult getDocument(
            @ToolParam(description = "One concrete index name (no wildcard, no list)")
            @ArgumentLimits(contractPattern = "OpenSearchIndexName", minLength = 1, maxLength = 255) String index,
            @ToolParam(description = "Document id as returned by search_logs")
            @ArgumentLimits(minLength = 1, maxLength = 256, pattern = DOCUMENT_ID_REGEX) String id) {
        IndexDocument document = useCase.getDocument(validIndexName(index), validDocumentId(id));
        return new DocumentResult(document.index(), document.id(), document.data(), document.truncated());
    }

    static IndexPattern validPattern(String value) {
        if (value == null || value.isEmpty() || value.length() > 255) {
            throw new InvalidToolArgumentException("index_pattern must be 1-255 characters");
        }
        if (value.equals("*") || value.equals("_all")) {
            throw new InvalidToolArgumentException("index_pattern must start with an index prefix; '*' and '_all' are not accepted");
        }
        if (value.startsWith("-")) {
            throw new InvalidToolArgumentException("index_pattern must not start with '-' (exclusions are not supported)");
        }
        if (value.startsWith(".")) {
            throw new InvalidToolArgumentException("index_pattern must not name hidden or system indices (leading '.')");
        }
        if (!INDEX_PATTERN.matcher(value).matches()) {
            throw new InvalidToolArgumentException("index_pattern must be one name of lower-case letters, digits and '-',"
                    + " optionally ending with '*'; lists, ':', '<', date math and other wildcards are not accepted");
        }
        boolean wildcard = value.endsWith("*");
        return new IndexPattern(wildcard ? value.substring(0, value.length() - 1) : value, wildcard);
    }

    static String validIndexName(String value) {
        if (value == null || !INDEX_NAME.matcher(value).matches()) {
            throw new InvalidToolArgumentException("index must be one concrete index name: 1-255 lower-case letters,"
                    + " digits, '.', '_' or '-', starting with a letter or digit");
        }
        return value;
    }

    static String validDocumentId(String value) {
        if (value == null || !DOCUMENT_ID.matcher(value).matches()) {
            throw new InvalidToolArgumentException("id must be 1-256 characters: letters, digits and _ - . : @ + =,"
                    + " not starting with '.'");
        }
        return value;
    }

    private static Pattern contractPattern(String definition) {
        String resource = "/mcp-contracts/schemas/common.schema.json";
        try (InputStream in = OpenSearchTools.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing: mcp-contracts must be on the classpath");
            }
            String pattern = JsonMapper.builder().build().readTree(in).path("$defs").path(definition)
                    .path("pattern").asString(null);
            if (pattern == null) {
                throw new IllegalStateException(definition + " has no pattern in common.schema.json");
            }
            return Pattern.compile(pattern);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String validQuery(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_QUERY) {
            throw new InvalidToolArgumentException("query must be 1-" + MAX_QUERY + " characters of text");
        }
        if (TextHygiene.firstProblem(value, false) != null) {
            throw new InvalidToolArgumentException("query must not contain control or invisible characters");
        }
        return value;
    }

    private static Instant validTime(String name, String value) {
        if (value == null || value.length() > MAX_TIME) {
            throw new InvalidToolArgumentException(name + " must be an ISO-8601 date-time with offset, e.g. 2026-10-27T08:00:00Z");
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            throw new InvalidToolArgumentException(name + " must be an ISO-8601 date-time with offset, e.g. 2026-10-27T08:00:00Z");
        }
    }

    private static int validSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidToolArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        return size;
    }
}
