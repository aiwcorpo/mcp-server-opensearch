package pl.aiwcorpo.mcp.opensearch.integration;

import pl.aiwcorpo.mcp.cedar.entities.ResourceTypes;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpenSearchResourceModel;

/** The declaration of the index type as the server publishes it, for tests that build a {@code QueryScope}. */
final class IndexTypes {

    static final ResourceTypes DECLARED = ResourceTypes.fromJson("""
            [{"type": "%s", "attributes": ["name", "tags"], "idPattern": "%s", "scopePattern": "%s",
              "classification": {"attribute": "tags", "kind": "set", "required": true}}]
            """.formatted(OpenSearchResourceModel.INDEX_TYPE, OpenSearchResourceModel.INDEX_NAME_REGEX,
            OpenSearchResourceModel.INDEX_PREFIX_REGEX));

    private IndexTypes() {
    }
}
