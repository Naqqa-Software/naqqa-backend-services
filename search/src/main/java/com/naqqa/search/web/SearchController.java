package com.naqqa.search.web;

import com.naqqa.elasticsearch.os.SearchModels.SearchQuery;
import com.naqqa.elasticsearch.os.SearchModels.SearchResult;
import com.naqqa.search.service.SearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${naqqa.search.path:/api/search}")
public class SearchController {

    private final SearchService service;

    public SearchController(SearchService service) {
        this.service = service;
    }

    @PostMapping
    public SearchResult search(@RequestBody(required = false) SearchQuery query) {
        return service.search(query);
    }
}
