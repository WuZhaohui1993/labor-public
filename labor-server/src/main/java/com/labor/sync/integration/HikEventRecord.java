package com.labor.sync.integration;

import java.time.Instant;

public record HikEventRecord(
        String eventId,
        String orgIndexCode,
        String hikPersonId,
        String personName,
        String idcardType,
        String idcardNumber,
        Instant eventTime,
        String direction,
        String checkType,
        String checkWay,
        String checkLocation,
        String longitude,
        String latitude,
        String doorIndexCode,
        String deviceIndexCode,
        String rawJson) {
}
