package com.labor.sync.integration;

import java.util.List;
import java.util.Map;

public interface LaborPlatformGateway {
    ConnectionResult testConnection(IntegrationConfig config);
    LaborPushResult push(IntegrationConfig config, String proCode, LaborPushOperation operation,
                         List<Map<String, Object>> payload);
}
