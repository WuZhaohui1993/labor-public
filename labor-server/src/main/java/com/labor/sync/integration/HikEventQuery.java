package com.labor.sync.integration;

import java.time.Instant;
import java.util.List;

public record HikEventQuery(String proCode, List<String> orgIndexCodes, Instant startTime, Instant endTime,
                            int pageNo, int pageSize) {
}
