package com.payflow.settlement.application;

import java.util.List;

public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public PageResult {
        content = List.copyOf(content);
        if (page < 0 || size < 1 || size > 100 || totalElements < 0) {
            throw new IllegalArgumentException("invalid page result");
        }
    }

    public long totalPages() {
        return totalElements == 0 ? 0 : (totalElements + size - 1) / size;
    }
}
