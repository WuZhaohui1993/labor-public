package com.labor.sync.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@ConditionalOnProperty(name = "app.integration.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockHikvisionGateway implements HikvisionGateway {
    @Override
    public ConnectionResult testConnection(IntegrationConfig config) {
        return new ConnectionResult(true, "模拟海康适配器可用；尚未连接真实海康环境");
    }

    @Override
    public List<HikOrganization> queryOrganizations(IntegrationConfig config) {
        return List.of(
                new HikOrganization("MOCK-ROOT", "模拟项目组织", null, "模拟项目组织", 1),
                new HikOrganization("MOCK-BUILD", "施工区域", "MOCK-ROOT", "模拟项目组织/施工区域", 1),
                new HikOrganization("MOCK-GATE", "一号门禁组", "MOCK-BUILD", "模拟项目组织/施工区域/一号门禁组", 1));
    }

    @Override
    public HikEventPage queryAttendanceEvents(IntegrationConfig config, HikEventQuery query) {
        if (query.pageNo() > 1 || query.startTime() == null || query.endTime() == null) {
            return new HikEventPage(List.of(), 0, 1, true);
        }
        Instant eventTime = query.endTime().truncatedTo(ChronoUnit.MINUTES);
        if (eventTime.isBefore(query.startTime())) {
            return new HikEventPage(List.of(), 0, 1, true);
        }
        String orgIndexCode = query.orgIndexCodes().isEmpty() ? "MOCK-ROOT" : query.orgIndexCodes().get(0);
        String eventId = "MOCK-" + orgIndexCode + "-" + eventTime.toEpochMilli();
        HikEventRecord record = new HikEventRecord(eventId, orgIndexCode, "MOCK-HIK-001", "模拟人员",
                null, null, eventTime, "JINCHANG_JINCHU", "ZHENGCHANG_KAOQINLEIBIE", "FACE_FANGSHI",
                "模拟门禁", null, null, "MOCK-DOOR-001", "MOCK-DEVICE-001",
                "{\"source\":\"mock\",\"eventId\":\"" + eventId + "\"}");
        return new HikEventPage(List.of(record), 1, 1, true);
    }
}
