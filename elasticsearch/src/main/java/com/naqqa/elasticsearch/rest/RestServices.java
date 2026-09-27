package com.naqqa.elasticsearch.rest;

import com.naqqa.elasticsearch.rest.cat.CatActionService;
import com.naqqa.elasticsearch.rest.cluster.ClusterAdminActionService;
import com.naqqa.elasticsearch.rest.document.DocumentActionService;
import com.naqqa.elasticsearch.rest.indices.IndexAdminActionService;
import com.naqqa.elasticsearch.rest.search.SearchActionService;

public record RestServices(
    DocumentActionService documents,
    SearchActionService search,
    IndexAdminActionService indices,
    ClusterAdminActionService cluster,
    CatActionService cat,
    String clusterName,
    String nodeName
) {
}
