package pl.aiwcorpo.mcp.opensearch.core.domain;

import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/** One field of an index mapping. The path is chosen by whoever shipped the documents (dynamic mapping), hence untrusted. */
public record MappingField(UntrustedText path, String type) {
}
