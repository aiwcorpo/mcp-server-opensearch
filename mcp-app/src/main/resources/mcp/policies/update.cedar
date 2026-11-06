// Changes only inside the index prefixes granted by the scope mapping, like reads. The current tools are read-only;
// the rules stay so that a later write tool does not start without a policy.

@id("opensearch-update-index-in-scope")
permit (
  principal in UserGroup::"mcp-opensearch-write",
  action == OpenSearch::Action::"update",
  resource is OpenSearch::Index
)
when { principal in resource.scopes };

@id("opensearch-delete-index-in-scope")
permit (
  principal in UserGroup::"mcp-opensearch-destructive",
  action == OpenSearch::Action::"delete",
  resource is OpenSearch::Index
)
when { principal in resource.scopes };
