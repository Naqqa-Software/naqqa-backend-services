package com.naqqa.elasticsearch.action.get;

import java.util.List;

public record MultiGetResponse(List<GetResponse> items) {
}
