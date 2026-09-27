package com.naqqa.elasticsearch.action.write;

import com.naqqa.elasticsearch.rest.document.DocumentActionService;

import java.util.List;

public record BulkResponse(List<DocumentActionService.BulkItemResult> items, long tookMillis) {
}
