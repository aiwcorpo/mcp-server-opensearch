package pl.aiwcorpo.mcp.opensearch.tools;

/** Result of {@code get_cluster_health}. */
public record ClusterHealthResult(String status, boolean timedOut, long nodes, long activeShards,
        long relocatingShards, long initializingShards, long unassignedShards, long pendingTasks,
        double activeShardsPercent) {
}
