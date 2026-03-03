package com.naqqa.entity.dto;

import java.util.List;

public record PagedResponse<T>(List<T> items, long total) {
}

