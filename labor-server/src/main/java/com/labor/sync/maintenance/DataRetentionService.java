package com.labor.sync.maintenance;

import com.labor.sync.common.CryptoService;
import com.labor.sync.hik.AttendanceSource;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.push.IntegrationCallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataRetentionService {
    private final IntegrationCallLogRepository callLogRepository;
    private final HikAttendanceEventRepository eventRepository;
    private final DataRetentionProperties properties;
    private final CryptoService cryptoService;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(cron = "${app.scheduling.data-retention-cron:0 30 18 * * *}", zone = "UTC")
    public void scheduledCleanup() {
        CleanupResult result = cleanup(Instant.now());
        if (result.deletedCallLogs() > 0 || result.archivedAttendancePayloads() > 0) {
            log.info("data retention completed deletedCallLogs={} archivedAttendancePayloads={}",
                    result.deletedCallLogs(), result.archivedAttendancePayloads());
        }
    }

    public CleanupResult cleanup(Instant now) {
        int deletedSuccess = deleteCallLogs(true,
                now.minus(Math.max(1, properties.getSuccessfulCallLogDays()), ChronoUnit.DAYS));
        int deletedFailure = deleteCallLogs(false,
                now.minus(Math.max(1, properties.getFailedCallLogDays()), ChronoUnit.DAYS));
        int archivedPayloads = archiveAttendancePayloads(
                now.minus(Math.max(1, properties.getAttendanceRawPayloadDays()), ChronoUnit.DAYS));
        return new CleanupResult(deletedSuccess, deletedFailure, archivedPayloads);
    }

    private int deleteCallLogs(boolean success, Instant cutoff) {
        int affected = 0;
        int batchSize = batchSize();
        for (int batch = 0; batch < maxBatches(); batch++) {
            List<Long> ids = callLogRepository.findRetentionIds(success, cutoff, PageRequest.of(0, batchSize));
            if (ids.isEmpty()) break;
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    callLogRepository.deleteAllByIdInBatch(ids));
            affected += ids.size();
            if (ids.size() < batchSize) break;
        }
        return affected;
    }

    private int archiveAttendancePayloads(Instant cutoff) {
        int affected = 0;
        int batchSize = batchSize();
        String archivedPayload = cryptoService.encrypt("{\"archived\":true,\"reason\":\"retention\"}");
        for (int batch = 0; batch < maxBatches(); batch++) {
            List<Long> ids = eventRepository.findRawPayloadRetentionIds(
                    AttendanceSource.HIKVISION, Set.of(MatchStatus.MATCHED, MatchStatus.RELEASED),
                    cutoff, PageRequest.of(0, batchSize));
            if (ids.isEmpty()) break;
            Integer updated = new TransactionTemplate(transactionManager).execute(status ->
                    eventRepository.archiveRawPayloads(ids, archivedPayload));
            affected += updated == null ? 0 : updated;
            if (ids.size() < batchSize) break;
        }
        return affected;
    }

    private int batchSize() {
        return Math.min(10000, Math.max(100, properties.getBatchSize()));
    }

    private int maxBatches() {
        return Math.min(100, Math.max(1, properties.getMaxBatchesPerRun()));
    }

    public record CleanupResult(int deletedSuccessfulCallLogs, int deletedFailedCallLogs,
                                int archivedAttendancePayloads) {
        public int deletedCallLogs() {
            return deletedSuccessfulCallLogs + deletedFailedCallLogs;
        }
    }
}
