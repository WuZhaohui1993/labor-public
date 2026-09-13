package com.labor.sync.integration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.integration")
public class IntegrationProperties {
    private boolean mockEnabled = true;
    private final Hikvision hikvision = new Hikvision();
    private final LaborPlatform laborPlatform = new LaborPlatform();
    private final Worker worker = new Worker();

    @Getter
    @Setter
    public static class Hikvision {
        private String eventPath = "/artemis/api/acs/v2/door/events";
        private String organizationPath = "/artemis/api/resource/v1/org/orgList";
        private int organizationPageSize = 500;
        private int organizationMaxPages = 100;
        private int pageSize = 100;
        private int maxPages = 1000;
        private int overlapSeconds = 120;
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 15;
        private String timePattern = "yyyy-MM-dd'T'HH:mm:ssXXX";
    }

    @Getter
    @Setter
    public static class LaborPlatform {
        private String tokenPath = "/auth/getTokenV2";
        private String projectPath = "/collBasicInfo/pushProjectBasicDataV2";
        private String companyPath = "/collBasicInfo/pushCollBasicDataV2";
        private String teamPath = "/collBasicInfo/pushTeamDataV2";
        private String personPath = "/personnelInfo/pushPersonnelDataV2";
        private String attendancePath = "/punchRecords/dataV2";
        /** First-stage integration sends only V3 required fields. */
        private boolean minimalPayload = true;
        private String authorizationPrefix = "";
        private long tokenTtlSeconds = 82800;
        private String checkTimePattern = "yyyy-MM-dd HH:mm:ss";
        private int connectTimeoutSeconds = 5;
        private int readTimeoutSeconds = 15;
    }

    @Getter
    @Setter
    public static class Worker {
        private int batchSize = 50;
        private int maxTasksPerRun = 1000;
        private int maxRetries = 5;
        private long retryBaseSeconds = 30;
    }
}
