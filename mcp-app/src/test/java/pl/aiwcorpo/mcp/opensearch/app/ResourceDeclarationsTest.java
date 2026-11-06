package pl.aiwcorpo.mcp.opensearch.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpenSearchResourceModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The patterns the tools publish for the prefix argument are the ones the declaration of the index type states. */
class ResourceDeclarationsTest {

    private static JsonNode declaration() throws IOException {
        try (InputStream in = ResourceDeclarationsTest.class.getClassLoader()
                .getResourceAsStream("mcp/policies/resource-types.json")) {
            JsonNode types = JsonMapper.builder().build().readTree(in);
            assertThat(types).hasSize(1);
            return types.get(0);
        }
    }

    @Test
    void theIndexNameIsTheIdPatternOfTheDeclaredType() throws IOException {
        JsonNode index = declaration();

        assertThat(index.get("type").asString()).isEqualTo(OpenSearchResourceModel.INDEX_TYPE);
        assertThat(index.get("idPattern").asString()).isEqualTo(OpenSearchResourceModel.INDEX_NAME_REGEX);
    }

    @Test
    void theIndexPrefixIsTheScopePatternOfTheDeclaredType() throws IOException {
        assertThat(declaration().get("scopePattern").asString()).isEqualTo(OpenSearchResourceModel.INDEX_PREFIX_REGEX);
    }

    @Test
    void theNameLengthLimitOfTheToolsIsTheLongestNameThePatternAllows() {
        assertThat(OpenSearchResourceModel.INDEX_NAME_MAX_LENGTH).isEqualTo(255);
        assertThat("a".repeat(255)).matches(OpenSearchResourceModel.INDEX_NAME_REGEX);
        assertThat("a".repeat(256)).doesNotMatch(OpenSearchResourceModel.INDEX_NAME_REGEX);
    }
}
