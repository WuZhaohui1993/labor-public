package com.labor.sync.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperty(name = "app.integration.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockLaborPlatformGateway implements LaborPlatformGateway {
    @Override
    public ConnectionResult testConnection(IntegrationConfig config) {
        return new ConnectionResult(true, "模拟劳务平台适配器可用；尚未连接真实劳务平台");
    }

    @Override
    public LaborPushResult push(IntegrationConfig config, String proCode, LaborPushOperation operation,
                                List<Map<String, Object>> payload) {
        if (payload == null || payload.isEmpty()) {
            return new LaborPushResult(false, "EMPTY_PAYLOAD", "推送数据不能为空", 400, "{\"success\":false}");
        }
        return new LaborPushResult(true, "200", "模拟推送成功", 200,
                "{\"code\":\"200\",\"success\":true,\"data\":true}");
    }
}
