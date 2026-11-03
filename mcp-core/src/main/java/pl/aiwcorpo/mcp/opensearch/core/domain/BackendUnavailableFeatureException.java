package pl.aiwcorpo.mcp.opensearch.core.domain;

/** The tool needs a feature the detected OpenSearch version is not known to have (degraded mode). */
public class BackendUnavailableFeatureException extends RuntimeException {

    public BackendUnavailableFeatureException() {
        super("feature not available on this backend version", null, false, false);
    }
}
