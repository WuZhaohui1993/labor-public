package com.labor.sync.maintenance;

import com.labor.sync.common.CryptoService;
import com.labor.sync.hik.AttendanceSource;
import com.labor.sync.hik.HikAttendanceEvent;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchMethod;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.push.IntegrationCallLog;
import com.labor.sync.push.IntegrationCallLogRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DataRetentionServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");

    @Autowired DataRetentionService retentionService;
    @Autowired DataRetentionProperties properties;
    @Autowired IntegrationCallLogRepository callLogRepository;
    @Autowired HikAttendanceEventRepository eventRepository;
    @Autowired CryptoService cryptoService;
    @Autowired EntityManager entityManager;

    @Test
    void oldLogsAreDeletedAndNormalizedAttendanceIsKeptWhileRawPayloadIsArchived() {
        properties.setSuccessfulCallLogDays(90);
        properties.setFailedCallLogDays(365);
        properties.setAttendanceRawPayloadDays(180);
        properties.setBatchSize(100);
        properties.setMaxBatchesPerRun(5);

        IntegrationCallLog oldSuccess = callLog(true, NOW.minusSeconds(100L * 86400));
        IntegrationCallLog recentSuccess = callLog(true, NOW.minusSeconds(10L * 86400));
        IntegrationCallLog oldFailure = callLog(false, NOW.minusSeconds(400L * 86400));
        callLogRepository.saveAllAndFlush(java.util.List.of(oldSuccess, recentSuccess, oldFailure));

        HikAttendanceEvent oldEvent = event("RETENTION-OLD", NOW.minusSeconds(200L * 86400));
        HikAttendanceEvent recentEvent = event("RETENTION-RECENT", NOW.minusSeconds(10L * 86400));
        eventRepository.saveAllAndFlush(java.util.List.of(oldEvent, recentEvent));

        DataRetentionService.CleanupResult result = retentionService.cleanup(NOW);
        entityManager.clear();

        assertThat(result.deletedSuccessfulCallLogs()).isEqualTo(1);
        assertThat(result.deletedFailedCallLogs()).isEqualTo(1);
        assertThat(result.archivedAttendancePayloads()).isEqualTo(1);
        assertThat(callLogRepository.findById(oldSuccess.getId())).isEmpty();
        assertThat(callLogRepository.findById(oldFailure.getId())).isEmpty();
        assertThat(callLogRepository.findById(recentSuccess.getId())).isPresent();
        assertThat(eventRepository.findById(oldEvent.getId())).get().satisfies(event -> {
            assertThat(event.isRawPayloadArchived()).isTrue();
            assertThat(cryptoService.decrypt(event.getRawPayloadEncrypted())).contains("archived");
            assertThat(event.getEventId()).isEqualTo("RETENTION-OLD");
            assertThat(event.getEventTime()).isEqualTo(oldEvent.getEventTime());
        });
        assertThat(eventRepository.findById(recentEvent.getId())).get()
                .extracting(HikAttendanceEvent::isRawPayloadArchived).isEqualTo(false);
    }

    private IntegrationCallLog callLog(boolean success, Instant createdAt) {
        IntegrationCallLog log = new IntegrationCallLog();
        log.setIntegrationType("TEST");
        log.setOperationType("RETENTION");
        log.setRequestPath("/test");
        log.setSuccess(success);
        log.setCreatedAt(createdAt);
        return log;
    }

    private HikAttendanceEvent event(String eventId, Instant eventTime) {
        HikAttendanceEvent event = new HikAttendanceEvent();
        event.setEventId(eventId);
        event.setProCode("P-RETENTION");
        event.setEventTime(eventTime);
        event.setDirection("JINCHANG_JINCHU");
        event.setCheckType("ZHENGCHANG_KAOQINLEIBIE");
        event.setCheckWay("FACE_FANGSHI");
        event.setRawPayloadEncrypted(cryptoService.encrypt("{\"large\":\"payload\"}"));
        event.setAttendanceSource(AttendanceSource.HIKVISION);
        event.setMatchStatus(MatchStatus.MATCHED);
        event.setMatchMethod(MatchMethod.HIK_PERSON_ID);
        event.setMatchReason("retention test");
        event.setMatchVersion(1);
        event.setReceivedAt(eventTime);
        return event;
    }
}
