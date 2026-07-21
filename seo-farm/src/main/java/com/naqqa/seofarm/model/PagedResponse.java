package com.naqqa.seofarm.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Public blog list envelope — mirrors naqqa-server's PagedResponse so the SEO site works unchanged. */
@Data
@Builder
public class PagedResponse<T> {
    private List<T> content;
    private Pagination pagination;

    @Data
    @Builder
    public static class Pagination {
        private int pageNumber;
        private int pageSize;
        private long totalElements;
        private int totalPages;
        private boolean last;
    }
}
