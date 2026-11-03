package pl.aiwcorpo.mcp.opensearch.core.domain;

/** Cluster-wide health without cluster or node names. */
public record ClusterHealth(String status, boolean timedOut, long nodes, long activeShards, long relocatingShards,
        long initializingShards, long unassignedShards, double activeShardsPercent) {
}
