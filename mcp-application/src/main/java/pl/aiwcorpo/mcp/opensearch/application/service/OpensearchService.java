package pl.aiwcorpo.mcp.opensearch.application.service;

import pl.aiwcorpo.mcp.opensearch.core.domain.OpensearchRecord;
import pl.aiwcorpo.mcp.opensearch.core.domain.RecordNotFoundException;
import pl.aiwcorpo.mcp.opensearch.core.port.in.OpensearchUseCase;
import pl.aiwcorpo.mcp.opensearch.core.port.out.OpensearchResourcePort;

/** Use cases of the server. A plain class: mcp-app makes it a bean, so this module stays free of Spring. */
public class OpensearchService implements OpensearchUseCase {

    private final OpensearchResourcePort resource;

    public OpensearchService(OpensearchResourcePort resource) {
        this.resource = resource;
    }

    @Override
    public OpensearchRecord getRecord(String recordId) {
        return resource.findById(recordId).orElseThrow(RecordNotFoundException::new);
    }
}
