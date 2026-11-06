// OpenSearch has no per-user rights in the backend beyond what the cluster's own security plugin enforces, so even
// reading is limited to the index prefixes an administrator mapped to a group. A grant of OpenSearch::Index/logs-team-a
// covers logs-team-a and logs-team-a-*, not logs-team-ab: the resolver gives an index the scope of every dash prefix
// of its name. Indices tagged restricted are closed by the forbid the platform generates from the classification.

@id("opensearch-read-in-scope")
permit (
  principal in UserGroup::"mcp-opensearch-read",
  action == OpenSearch::Action::"read",
  resource is OpenSearch::Index
)
when { principal in resource.scopes };
