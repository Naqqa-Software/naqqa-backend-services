package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.rest.document.DocumentActionService;

import java.util.List;

public record BulkRequest(List<DocumentActionService.BulkItem> items, String defaultIndex, String globalRefresh) {
}
