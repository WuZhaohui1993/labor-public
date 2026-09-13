package com.labor.sync.maintenance;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.retention")
public class DataRetentionProperties {
    private int successfulCallLogDays = 30;
    private int failedCallLogDays = 365;
    private int attendanceRawPayloadDays = 180;
    private int batchSize = 2000;
    private int maxBatchesPerRun = 20;
}
