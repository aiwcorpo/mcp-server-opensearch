package pl.aiwcorpo.mcp.opensearch.core.port.in;

import pl.aiwcorpo.mcp.opensearch.core.domain.OpensearchRecord;

/** Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by mcp-tools. */
public interface OpensearchUseCase {

    /**
     * @throws pl.aiwcorpo.mcp.opensearch.core.domain.RecordNotFoundException when the record does not exist or
     *         the caller's backend account may not see it
     */
    OpensearchRecord getRecord(String recordId);
}
