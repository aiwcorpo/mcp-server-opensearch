package pl.aiwcorpo.mcp.opensearch.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.jspecify.annotations.Nullable;
import pl.aiwcorpo.mcp.opensearch.core.domain.AggregateQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.BackendUnavailableFeatureException;
import pl.aiwcorpo.mcp.opensearch.core.domain.ClusterHealth;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexMapping;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogCount;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogHit;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogPage;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpenSearchFeatures;
import pl.aiwcorpo.mcp.opensearch.core.domain.QueryCheck;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchAfter;
import pl.aiwcorpo.mcp.opensearch.core.domain.GroupBy;
import pl.aiwcorpo.mcp.opensearch.core.domain.IndexDocument;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogBuckets;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogFilter;
import pl.aiwcorpo.mcp.opensearch.core.domain.LogQuery;
import pl.aiwcorpo.mcp.opensearch.core.domain.ResolvedIndex;
import pl.aiwcorpo.mcp.opensearch.core.domain.SearchLimits;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpenSearchUseCase;
import pl.aiwcorpo.mcp.platform.capability.Capabilities;
import pl.aiwcorpo.mcp.platform.capability.BackendCapabilities;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.paging.CursorCodec;
import pl.aiwcorpo.mcp.platform.paging.PagedResult;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP tools of this server. Every argument is untrusted input from a model. Parameter names are the public
 * argument names of the input schema, hence {@code index_prefix}; the limits in {@link ArgumentLimits} are
 * published in the schema by {@code SchemaLimitedToolCallbacks} and enforced by the starter before the method
 * runs, and the checks below repeat what the tool relies on.
 *
 * <p>The index argument of the two search tools is an index prefix, never a pattern: the contract pattern
 * {@code OpenSearchIndexPrefix} lets through one plain name and nothing OpenSearch would read as more (lists,
 * exclusions, date math, remote clusters, {@code _all}, {@code *}, hidden {@code .} indices). It only picks from
 * the concrete indices of the caller's scope ({@code OpenSearchService}); it is never put into a request.
 *
 * <p>The model gives values, never a query: text, a level, a service. The server builds the query from them
 * ({@code OpenSearchAdapter}) and limits it to the scope the caller's grants allow. Documents the policy refuses
 * are removed from search results by the starter (the {@code @ResourceRef} on the hit's index) and left out of
 * aggregations by this class, because counts cannot be corrected afterwards.
 */
@Service
public class OpenSearchTools {

    public static final String SEARCH_LOGS = "search_logs";
    public static final String AGGREGATE_LOGS = "aggregate_logs";
    public static final String GET_DOCUMENT = "get_document";
    public static final String COUNT_LOGS = "count_logs";
    public static final String LIST_INDICES = "list_indices";
    public static final String GET_INDEX_MAPPING = "get_index_mapping";
    public static final String VALIDATE_QUERY = "validate_query";
    public static final String GET_CLUSTER_HEALTH = "get_cluster_health";
    public static final String GET_BACKEND_INFO = "get_backend_info";

    /** Document ids that fit a URL path segment; the same text goes to the input schema. */
    private static final String DOCUMENT_ID_REGEX = "^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$";
    /**
     * {@code OpenSearchIndexName} read from the contract on the classpath, the same pattern the input schema
     * publishes. Since contracts 2.1 (CC-074) it excludes a leading dot itself.
     */
    private static final Pattern INDEX_NAME = contractPattern("OpenSearchIndexName");
    /**
     * {@code OpenSearchIndexPrefix}: one name without wildcard, comma, colon, date math or leading dot, which cannot
     * be {@code _all} or a bare {@code *}. The input schema publishes the same pattern.
     */
    private static final Pattern INDEX_PREFIX = contractPattern("OpenSearchIndexPrefix");
    /** OpenSearch ids are free text up to 512 bytes; this keeps ids that fit a URL path segment unescaped. */
    private static final Pattern DOCUMENT_ID = Pattern.compile(DOCUMENT_ID_REGEX);
    /** The levels of the log field, upper case; the term query on the level field ignores case, so any mapping works. */
    private static final String LEVEL_REGEX = "^(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)$";
    private static final String SERVICE_REGEX = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$";
    private static final String GROUP_BY_REGEX = "^(?:level|service|host)$";
    private static final Pattern LEVEL = Pattern.compile(LEVEL_REGEX);
    private static final Pattern SERVICE = Pattern.compile(SERVICE_REGEX);
    private static final int MAX_QUERY = 512;
    private static final int DEFAULT_BUCKETS = 10;
    private static final int MAX_BUCKETS = 50;
    private static final int MAX_TIME = 40;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    /** Backend pages one {@code search_logs} call may read to fill its page. */
    private static final int MAX_READS = 5;
    private static final int DEFAULT_INDEX_PAGE = 25;
    private static final int MAX_INDEX_PAGE = 50;

    private final OpenSearchUseCase useCase;
    private final Duration maxRange;
    private final Duration callBudget;
    private final ResourcePolicy policy;
    private final OpenSearchIndexResolver indices;
    private final CursorCodec cursors;
    private final BackendCapabilities capabilities;

    public OpenSearchTools(OpenSearchUseCase useCase, SearchLimits limits, ResourcePolicy policy,
            OpenSearchIndexResolver indices, CursorCodec cursors, BackendCapabilities capabilities) {
        this.useCase = useCase;
        this.maxRange = limits.maxRange();
        this.callBudget = limits.callBudget();
        this.policy = policy;
        this.indices = indices;
        this.cursors = cursors;
        this.capabilities = capabilities;
    }

    /**
     * The prefix is an argument and not a {@code @ResourceRef}: it names a set of indices, not one. A caller whose
     * grants exclude it gets the same empty result as for a prefix with no index. The scope comes from
     * {@code QueryScope}; what the policy refuses in a hit is removed afterwards.
     */
    @Tool(name = SEARCH_LOGS, description = "Searches log documents in the indices of one"
            + " index prefix within a time range. All words of text must occur in the log message; the optional"
            + " level and service narrow it to exact values. Returns up to size entries, newest first, each with index,"
            + " id, timestamp and the configured log fields under 'data'. No total count. When more entries may"
            + " follow, nextCursor is set; pass it back with the same other arguments for the next page.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Search logs")
    public PagedResult<LogHitView> searchLogs(
            @ToolParam(description = "Index prefix p: covers the index named p and every index named p-..., no wildcard")
            @ArgumentLimits(contractPattern = "OpenSearchIndexPrefix", minLength = 1, maxLength = 255) String index_prefix,
            @ToolParam(description = "Words that must all occur in the log message; characters such as + - | * \" are plain text")
            @ArgumentLimits(minLength = 1, maxLength = MAX_QUERY) String text,
            @ToolParam(description = "Log level, upper case: TRACE, DEBUG, INFO, WARN, ERROR or FATAL", required = false)
            @ArgumentLimits(minLength = 4, maxLength = 5, pattern = LEVEL_REGEX) String level,
            @ToolParam(description = "Exact service name", required = false)
            @ArgumentLimits(minLength = 1, maxLength = 64, pattern = SERVICE_REGEX) String service,
            @ToolParam(description = "Start of the time range, inclusive, ISO-8601 with offset, e.g. 2026-10-27T08:00:00Z")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String from,
            @ToolParam(description = "End of the time range, exclusive, ISO-8601 with offset, later than from")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String to,
            @ToolParam(description = "Number of entries, 1-100, default 20", required = false)
            @ArgumentLimits(minimum = 1, maximum = MAX_SIZE) Integer size,
            @ToolParam(description = "nextCursor of the previous page of the same query", required = false)
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) String cursor) {
        requireFeature(OpenSearchFeatures.SEARCH);
        if (cursor != null) {
            requireFeature(OpenSearchFeatures.SEARCH_AFTER);
        }
        String prefix = validPrefix(index_prefix);
        LogFilter filter = new LogFilter(validText(text), validLevel(level), validService(service));
        Instant start = validTime("from", from);
        Instant end = validTime("to", to);
        validRange(start, end);
        int count = validSize(size);
        String query = CursorCodec.fingerprint(SEARCH_LOGS, prefix, filter.text(), filter.level(), filter.service(),
                start.toString(), end.toString(), Integer.toString(count));
        SearchAfter after = cursor == null ? null : position(cursors.open(cursor, SEARCH_LOGS, query));
        Predicate<String> readable = index -> policy.allows(indices.resource(index));
        // The hits the policy refuses are removed afterwards by the starter; they are dropped here as well, so that the
        // cursor can be made from the position of the last hit the caller gets and never from one it must not learn of.
        // A page thinned out this way is filled from the next backend page, up to MAX_READS reads.
        List<LogHit> allowed = new ArrayList<>();
        SearchAfter lastRaw = null;
        SearchAfter position = after;
        long began = System.nanoTime();
        for (int reads = 1; reads <= MAX_READS; reads++) {
            if (reads > 1 && System.nanoTime() - began > callBudget.toNanos()) {
                break;
            }
            LogPage page = useCase.searchLogs(new LogQuery(prefix, filter, start, end, count, position), readable);
            page.hits().stream().filter(hit -> readable.test(hit.index())).forEach(allowed::add);
            lastRaw = page.next();
            if (lastRaw == null || allowed.size() >= count) {
                break;
            }
            position = lastRaw;
        }
        boolean trimmed = allowed.size() > count;
        SearchLogsResult kept = SearchLogsResult.of(trimmed ? allowed.subList(0, count) : allowed);
        SearchAfter resume;
        if (kept.hits().isEmpty()) {
            // Nothing the caller may see in MAX_READS pages: the only position left is the one after the last page read.
            resume = lastRaw;
        } else if (kept.truncated() || trimmed || lastRaw != null) {
            resume = kept.lastPosition();
        } else {
            resume = null;
        }
        String next = resume == null ? null : cursors.issue(SEARCH_LOGS, query, resume.encode());
        boolean moreWithoutCursor = resume == null && (kept.truncated() || trimmed || lastRaw != null);
        return new PagedResult<>(kept.hits(), next, kept.truncated(), moreWithoutCursor ? List.of("cursor_unavailable") : List.of());
    }

    /**
     * Counts per level, service or host. Only indices the policy allows are counted (each one is judged like a
     * {@code get_document} target), and only concrete indices: a count cannot be corrected after the fact the way
     * a list of hits can, and an alias would bring in indices the prefix does not name.
     */
    @Tool(name = AGGREGATE_LOGS, description = "Counts log documents in the indices of one index prefix"
            + " within a time range, grouped by level, service or host. Returns up to size groups, the biggest first,"
            + " each with the value and its document count, and otherDocuments, the documents in groups left out. The optional text, level and service narrow the"
            + " documents as in search_logs. Aliases and data streams are not counted.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Aggregate logs")
    public AggregateLogsResult aggregateLogs(
            @ToolParam(description = "Index prefix p: covers the index named p and every index named p-..., no wildcard")
            @ArgumentLimits(contractPattern = "OpenSearchIndexPrefix", minLength = 1, maxLength = 255) String index_prefix,
            @ToolParam(description = "What to group by: level, service or host")
            @ArgumentLimits(minLength = 4, maxLength = 7, pattern = GROUP_BY_REGEX) String group_by,
            @ToolParam(description = "Words that must all occur in the log message", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_QUERY) String text,
            @ToolParam(description = "Log level, upper case: TRACE, DEBUG, INFO, WARN, ERROR or FATAL", required = false)
            @ArgumentLimits(minLength = 4, maxLength = 5, pattern = LEVEL_REGEX) String level,
            @ToolParam(description = "Exact service name", required = false)
            @ArgumentLimits(minLength = 1, maxLength = 64, pattern = SERVICE_REGEX) String service,
            @ToolParam(description = "Start of the time range, inclusive, ISO-8601 with offset, e.g. 2026-10-27T08:00:00Z")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String from,
            @ToolParam(description = "End of the time range, exclusive, ISO-8601 with offset, later than from")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String to,
            @ToolParam(description = "Number of groups, 1-50, default 10", required = false)
            @ArgumentLimits(minimum = 1, maximum = MAX_BUCKETS) Integer size) {
        requireFeature(OpenSearchFeatures.SEARCH);
        String prefix = validPrefix(index_prefix);
        GroupBy groupBy = validGroupBy(group_by);
        LogFilter filter = new LogFilter(text == null ? null : validText(text), validLevel(level), validService(service));
        Instant start = validTime("from", from);
        Instant end = validTime("to", to);
        validRange(start, end);
        int buckets = size == null ? DEFAULT_BUCKETS : size;
        if (buckets < 1 || buckets > MAX_BUCKETS) {
            throw new InvalidToolArgumentException("size must be between 1 and " + MAX_BUCKETS);
        }
        LogBuckets found = useCase.aggregateLogs(
                new AggregateQuery(prefix, filter, start, end, groupBy, buckets),
                index -> policy.allows(indices.resource(index)));
        return AggregateLogsResult.of(prefix, start.toString(), end.toString(), groupBy.name().toLowerCase(java.util.Locale.ROOT), found.buckets(), found.otherDocuments());
    }

    /**
     * The index is a {@code @ResourceRef}: before this method runs the starter has the resolver check it with the
     * caller's own credential and the scope, and the policy judge the index OpenSearch names (the real index when the
     * argument was an alias). Missing, forbidden and refused all end as {@code not_found_or_denied}.
     */
    @Tool(name = GET_DOCUMENT, description = "Returns one document by index and id: index, id"
            + " and the configured fields under 'data'.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get document")
    public DocumentResult getDocument(
            @ToolParam(description = "One concrete index name (no wildcard, no list)")
            @ArgumentLimits(contractPattern = "OpenSearchIndexName", minLength = 1, maxLength = 255)
            @ResourceRef(type = OpenSearchIndexResolver.TYPE) String index,
            @ToolParam(description = "Document id as returned by search_logs")
            @ArgumentLimits(minLength = 1, maxLength = 256, pattern = DOCUMENT_ID_REGEX) String id) {
        requireFeature(OpenSearchFeatures.DOCUMENT);
        validIndexName(index);
        // The index the resolver fetched for the policy: the one that was judged is the one that is read.
        ResolvedIndex resolved = ResourceContext.required()
                .fetched(OpenSearchIndexResolver.TYPE, index, ResolvedIndex.class).orElseThrow();
        IndexDocument document = useCase.getDocument(resolved.name(), validDocumentId(id));
        return new DocumentResult(document.index(), document.id(), document.data(), document.truncated());
    }

    @Tool(name = COUNT_LOGS, description = "Counts log documents in the indices of one index prefix within a"
            + " time range. The optional text, level and service narrow the documents as in search_logs. Returns the"
            + " count and whether it is exact. Indices the caller may not read are not counted.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Count logs")
    public CountLogsResult countLogs(
            @ToolParam(description = "Index prefix p: covers the index named p and every index named p-..., no wildcard")
            @ArgumentLimits(contractPattern = "OpenSearchIndexPrefix", minLength = 1, maxLength = 255) String index_prefix,
            @ToolParam(description = "Words that must all occur in the log message", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_QUERY) String text,
            @ToolParam(description = "Log level, upper case: TRACE, DEBUG, INFO, WARN, ERROR or FATAL", required = false)
            @ArgumentLimits(minLength = 4, maxLength = 5, pattern = LEVEL_REGEX) String level,
            @ToolParam(description = "Exact service name", required = false)
            @ArgumentLimits(minLength = 1, maxLength = 64, pattern = SERVICE_REGEX) String service,
            @ToolParam(description = "Start of the time range, inclusive, ISO-8601 with offset, e.g. 2026-10-27T08:00:00Z")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String from,
            @ToolParam(description = "End of the time range, exclusive, ISO-8601 with offset, later than from")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String to) {
        requireFeature(OpenSearchFeatures.COUNT);
        LogQuery query = optionalTextQuery(index_prefix, text, level, service, from, to);
        LogCount counted = useCase.countLogs(query, index -> policy.allows(indices.resource(index)));
        return new CountLogsResult(query.prefix(), query.from().toString(), query.to().toString(), counted.count(),
                counted.exact());
    }

    @Tool(name = LIST_INDICES, description = "Lists the indices of one index prefix that the caller may read, by"
            + " name, with their health. Pass nextCursor from the previous result for the next page.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("List indices")
    public PagedResult<IndexView> listIndices(
            @ToolParam(description = "Index prefix p: covers the index named p and every index named p-..., no wildcard")
            @ArgumentLimits(contractPattern = "OpenSearchIndexPrefix", minLength = 1, maxLength = 255) String index_prefix,
            @ToolParam(description = "Number of indices, 1-50, default 25", required = false)
            @ArgumentLimits(minimum = 1, maximum = MAX_INDEX_PAGE) Integer size,
            @ToolParam(description = "nextCursor of the previous page of the same query", required = false)
            @ArgumentLimits(maxLength = CursorCodec.MAX_CURSOR_LENGTH, pattern = CursorCodec.ARGUMENT_PATTERN) String cursor) {
        requireFeature(OpenSearchFeatures.LIST_INDICES);
        String prefix = validPrefix(index_prefix);
        int limit = size == null ? DEFAULT_INDEX_PAGE : size;
        if (limit < 1 || limit > MAX_INDEX_PAGE) {
            throw new InvalidToolArgumentException("size must be between 1 and " + MAX_INDEX_PAGE);
        }
        String query = CursorCodec.fingerprint(LIST_INDICES, prefix, Integer.toString(limit));
        String after = cursor == null ? null : validIndexName(cursors.open(cursor, LIST_INDICES, query));
        IndexPage page = useCase.listIndices(prefix, after, limit, index -> policy.allows(indices.resource(index)));
        List<IndexView> views = page.indices().stream()
                .map(row -> new IndexView(row.name(), row.health())).toList();
        String next = page.lastName() == null ? null : cursors.issue(LIST_INDICES, query, page.lastName());
        return PagedResult.of(views, next);
    }

    @Tool(name = GET_INDEX_MAPPING, description = "Returns the fields of one index that this server queries, returns"
            + " or sorts by, with their mapping type, nested fields as dotted paths. Other fields of the index are not listed.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get index mapping")
    public IndexMappingResult getIndexMapping(
            @ToolParam(description = "One concrete index name (no wildcard, no list)")
            @ArgumentLimits(contractPattern = "OpenSearchIndexName", minLength = 1, maxLength = 255)
            @ResourceRef(type = OpenSearchIndexResolver.TYPE) String index) {
        requireFeature(OpenSearchFeatures.MAPPING);
        validIndexName(index);
        ResolvedIndex resolved = ResourceContext.required()
                .fetched(OpenSearchIndexResolver.TYPE, index, ResolvedIndex.class).orElseThrow();
        IndexMapping mapping = useCase.getIndexMapping(resolved.name());
        return new IndexMappingResult(mapping.index(), mapping.fields(), mapping.truncated());
    }

    @Tool(name = VALIDATE_QUERY, description = "Asks OpenSearch whether it accepts the query that search_logs would"
            + " send for these arguments and how it reads it. Returns valid, the number of indices checked, the"
            + " indices that rejected it and the explanation of the first one. Reads no documents.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Validate query")
    public QueryCheckResult validateQuery(
            @ToolParam(description = "Index prefix p: covers the index named p and every index named p-..., no wildcard")
            @ArgumentLimits(contractPattern = "OpenSearchIndexPrefix", minLength = 1, maxLength = 255) String index_prefix,
            @ToolParam(description = "Words that must all occur in the log message", required = false)
            @ArgumentLimits(minLength = 1, maxLength = MAX_QUERY) String text,
            @ToolParam(description = "Log level, upper case: TRACE, DEBUG, INFO, WARN, ERROR or FATAL", required = false)
            @ArgumentLimits(minLength = 4, maxLength = 5, pattern = LEVEL_REGEX) String level,
            @ToolParam(description = "Exact service name", required = false)
            @ArgumentLimits(minLength = 1, maxLength = 64, pattern = SERVICE_REGEX) String service,
            @ToolParam(description = "Start of the time range, inclusive, ISO-8601 with offset, e.g. 2026-10-27T08:00:00Z")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String from,
            @ToolParam(description = "End of the time range, exclusive, ISO-8601 with offset, later than from")
            @ArgumentLimits(minLength = 20, maxLength = MAX_TIME) String to) {
        requireFeature(OpenSearchFeatures.VALIDATE_QUERY);
        QueryCheck check = useCase.validateQuery(optionalTextQuery(index_prefix, text, level, service, from, to),
                index -> policy.allows(indices.resource(index)));
        return new QueryCheckResult(check.valid(), check.checked(), check.invalidIndices(), check.explanation());
    }

    @Tool(name = GET_CLUSTER_HEALTH, description = "Returns the health of the whole cluster: status, number of nodes"
            + " and shard counts. No names of the cluster, nodes or indices.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get cluster health")
    public ClusterHealthResult getClusterHealth() {
        requireFeature(OpenSearchFeatures.CLUSTER_HEALTH);
        ClusterHealth health = useCase.clusterHealth();
        return new ClusterHealthResult(health.status(), health.timedOut(), health.nodes(), health.activeShards(),
                health.relocatingShards(), health.initializingShards(), health.unassignedShards(),
                health.activeShardsPercent());
    }

    @Tool(name = GET_BACKEND_INFO, description = "Returns what this server knows about the OpenSearch behind it: the"
            + " version found at startup, whether it is a tested one (supported) or not (degraded, unavailable), and the"
            + " features available. Makes no request to OpenSearch.")
    @ToolRisk(RiskLevel.READ_ONLY)
    @ToolTitle("Get backend info")
    public BackendInfoResult getBackendInfo() {
        Capabilities found = capabilities.of(OpenSearchFeatures.PROBE);
        return new BackendInfoResult(found.state().name().toLowerCase(Locale.ROOT), found.backendVersion(),
                found.features().stream().sorted().toList(), found.detail());
    }

    private LogQuery optionalTextQuery(String indexPrefix, String text, String level, String service, String from,
            String to) {
        String prefix = validPrefix(indexPrefix);
        LogFilter filter = new LogFilter(text == null ? null : validText(text), validLevel(level), validService(service));
        Instant start = validTime("from", from);
        Instant end = validTime("to", to);
        validRange(start, end);
        return new LogQuery(prefix, filter, start, end, 1);
    }

    private void requireFeature(String feature) {
        if (!capabilities.of(OpenSearchFeatures.PROBE).supports(feature)) {
            throw new BackendUnavailableFeatureException();
        }
    }

    /** The position inside a cursor this server issued; the signature already vouches for the text, the parse is the second line. */
    private static SearchAfter position(String state) {
        try {
            return SearchAfter.decode(state);
        } catch (IllegalArgumentException refused) {
            throw new InvalidToolArgumentException(CursorCodec.REFUSED_MESSAGE);
        }
    }

    static String validPrefix(String value) {
        if (value == null || !INDEX_PREFIX.matcher(value).matches()) {
            throw new InvalidToolArgumentException("index_prefix must be one index prefix: lower-case letters, digits,"
                    + " '.', '_' or '-', starting with a letter or digit; wildcards, lists, ':', '<', date math and"
                    + " '_all' are not accepted");
        }
        return value;
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

    private static String validText(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_QUERY) {
            throw new InvalidToolArgumentException("text must be 1-" + MAX_QUERY + " characters");
        }
        if (TextHygiene.firstProblem(value, false) != null) {
            throw new InvalidToolArgumentException("text must not contain control or invisible characters");
        }
        return value;
    }

    private static String validLevel(String value) {
        if (value != null && !LEVEL.matcher(value).matches()) {
            throw new InvalidToolArgumentException("level must be one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL");
        }
        return value;
    }

    private static String validService(String value) {
        if (value != null && !SERVICE.matcher(value).matches()) {
            throw new InvalidToolArgumentException("service must be 1-64 characters: letters, digits, '.', '_' or '-',"
                    + " starting with a letter or digit");
        }
        return value;
    }

    private static GroupBy validGroupBy(String value) {
        try {
            return GroupBy.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new InvalidToolArgumentException("group_by must be one of level, service, host");
        }
    }

    private void validRange(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new InvalidToolArgumentException("to must be later than from");
        }
        if (Duration.between(start, end).compareTo(maxRange) > 0) {
            throw new InvalidToolArgumentException("the time range from-to may not exceed " + maxRange.toHours() + " hours");
        }
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
