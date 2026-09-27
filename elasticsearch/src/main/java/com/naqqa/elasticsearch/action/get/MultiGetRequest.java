package com.naqqa.elasticsearch.action.get;

import java.util.List;

public record MultiGetRequest(List<Item> items) {

    public record Item(String index, String id) {
    }
}
