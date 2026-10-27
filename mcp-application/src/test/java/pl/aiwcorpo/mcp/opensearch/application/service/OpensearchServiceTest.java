package pl.aiwcorpo.mcp.opensearch.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpensearchRecord;
import pl.aiwcorpo.mcp.opensearch.core.domain.RecordNotFoundException;

class OpensearchServiceTest {

    private final Map<String, OpensearchRecord> backend = Map.of("ABC-1", new OpensearchRecord("ABC-1", "ABC", "First", "open"));
    private final OpensearchService service = new OpensearchService(id -> Optional.ofNullable(backend.get(id)));

    @Test
    void returnsTheRecordTheBackendShows() {
        assertThat(service.getRecord("ABC-1").title()).isEqualTo("First");
    }

    @Test
    void aRecordTheBackendDoesNotShowIsNotFoundOrDenied() {
        assertThatThrownBy(() -> service.getRecord("ABC-2")).isInstanceOf(RecordNotFoundException.class);
    }
}
