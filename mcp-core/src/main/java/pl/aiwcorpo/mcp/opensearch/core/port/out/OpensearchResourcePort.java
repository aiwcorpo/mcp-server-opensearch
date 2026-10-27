package pl.aiwcorpo.mcp.opensearch.core.port.out;

import java.util.Optional;
import pl.aiwcorpo.mcp.opensearch.core.domain.OpensearchRecord;

/** Outbound port to the backend system, implemented in mcp-integration. */
public interface OpensearchResourcePort {

    /** Empty when the backend does not show the record to the caller, whether it exists or not. */
    Optional<OpensearchRecord> findById(String recordId);
}
