Responses of the OpenSearch REST API as StubBackend serves them, one directory per product version.

They are written from the documented response shapes of GET /, _cat/indices?format=json, _mapping, _validate/query,
_cluster/health and _search, not recorded from a cluster: no cluster was available when they were made. Fields the server
does not read are kept (cluster name, build data, shard statistics) so that the tests also cover ignoring them.
%INDEX% and %INDICES% are replaced by the stub with the index named in the request.
Replace them with recordings (WireMock record mode against opensearchproject/opensearch 2.19.x and 3.x, anonymized)
when a cluster or the images may be used.
