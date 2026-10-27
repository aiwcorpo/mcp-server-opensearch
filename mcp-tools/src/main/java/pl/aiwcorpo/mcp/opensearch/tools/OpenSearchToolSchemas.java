package pl.aiwcorpo.mcp.opensearch.tools;

import java.util.Map;
import java.util.Set;

/**
 * Input schemas of the tools, written by hand: Spring AI's generated schema has neither length limits nor
 * patterns, and the gateway and the approved definition (C6) should carry them. The tool methods check the
 * same rules again, because a schema is advice to the client, not a check on the server.
 */
public final class OpenSearchToolSchemas {

    public static final String SEARCH_LOGS = "search_logs";
    public static final String GET_DOCUMENT = "get_document";

    private static final String SEARCH_LOGS_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["index_pattern","query","from","to"],
             "properties":{
              "index_pattern":{"type":"string","minLength":1,"maxLength":255,"pattern":"^[a-z0-9-]+\\\\*?$",
               "description":"One index name, or one name followed by '*'. Lower-case letters, digits and '-'. Must start with an index prefix served by this server."},
              "query":{"type":"string","minLength":1,"maxLength":512,
               "description":"Text searched in the configured log fields (OpenSearch simple_query_string: words, \\"phrases\\", +, |, -, trailing *)."},
              "from":{"type":"string","format":"date-time","minLength":20,"maxLength":40,
               "description":"Start of the time range, inclusive. ISO-8601 date-time with offset, e.g. 2026-10-27T08:00:00Z."},
              "to":{"type":"string","format":"date-time","minLength":20,"maxLength":40,
               "description":"End of the time range, exclusive. ISO-8601 date-time with offset. Later than from, within the server's maximum range."},
              "size":{"type":"integer","minimum":1,"maximum":100,"default":20,
               "description":"Number of log entries to return, newest first. 1-100, default 20."}
             }}""";

    private static final String GET_DOCUMENT_SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["index","id"],
             "properties":{
              "index":{"type":"string","minLength":1,"maxLength":255,"pattern":"^[a-z0-9][a-z0-9._-]{0,254}$",
               "description":"One concrete index name (no wildcard, no list). Must start with an index prefix served by this server."},
              "id":{"type":"string","minLength":1,"maxLength":256,"pattern":"^[A-Za-z0-9_-][A-Za-z0-9_.:@+=-]{0,255}$",
               "description":"Document id as returned by search_logs."}
             }}""";

    private static final Map<String, String> SCHEMAS = Map.of(SEARCH_LOGS, SEARCH_LOGS_SCHEMA, GET_DOCUMENT, GET_DOCUMENT_SCHEMA);

    private static final Map<String, Set<String>> ARGUMENTS = Map.of(
            SEARCH_LOGS, Set.of("index_pattern", "query", "from", "to", "size"),
            GET_DOCUMENT, Set.of("index", "id"));

    private OpenSearchToolSchemas() {
    }

    public static String schemaOf(String tool) {
        String schema = SCHEMAS.get(tool);
        if (schema == null) {
            throw new IllegalStateException("no input schema for tool " + tool);
        }
        return schema;
    }

    /** Argument names the schema declares; empty for a tool this server does not have. */
    public static Set<String> argumentsOf(String tool) {
        return ARGUMENTS.getOrDefault(tool, Set.of());
    }
}
