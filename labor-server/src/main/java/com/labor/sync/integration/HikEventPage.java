package com.labor.sync.integration;

import java.util.List;

public record HikEventPage(List<HikEventRecord> items, long total, int totalPages, boolean lastPage) {
    public HikEventPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
