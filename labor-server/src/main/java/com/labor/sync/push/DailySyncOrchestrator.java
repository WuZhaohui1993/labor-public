package com.labor.sync.push;

import com.labor.sync.attendance.AttendanceCompletionService;
import com.labor.sync.hik.HikCollectionService;
import com.labor.sync.integration.IntegrationProperties;
import com.labor.sync.matching.PersonMatchingService;
import com.labor.sync.workspace.ProjectSyncSetting;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DailySyncOrchestrator {
    private final ProjectSyncSettingRepository settingRepository;
    private final HikCollectionService collectionService;
    private final PersonMatchingService matchingService;
    private final AttendanceCompletionService completionService;
    private final PushTaskService taskService;
    private final IntegrationProperties properties;
    private final PlatformTransactionManager transactionManager;

    @Scheduled(cron = "${app.scheduling.push-check-cron:0 40 * * * *}", zone = "UTC")
    public void dispatchDueProjects() {
        settingRepository.findByPushEnabledTrueOrderByProjectProCodeAsc().forEach(setting -> {
            if (!setting.isSyncStarted()) return;
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(setting.getZoneId()));
            ZonedDateTime slot = nextDueSlot(setting, now);
            try {
                if (slot != null) {
                    SlotRunResult result = runProjectSlot(setting, slot);
                    if (result.dispatch().completed()) {
                        markCompleted(setting.getId(), slot);
                        log.info("project half-day slot completed proCode={} windowStart={} windowEnd={} "
                                        + "collected={} matched={} targetRate={} completionGenerated={} "
                                        + "remainingAttendance={}",
                                result.proCode(), result.windowStart(), result.windowEnd(),
                                result.collection().inserted(), result.matching().matched(),
                                result.completion().targetRate(), result.completion().newlyGeneratedCount(),
                                result.dispatch().remainingAttendance());
                    } else {
                        log.warn("project half-day slot remains pending proCode={} windowStart={} windowEnd={} "
                                        + "dependenciesReady={} remainingAttendance={}",
                                result.proCode(), result.windowStart(), result.windowEnd(),
                                result.dispatch().dependenciesReady(), result.dispatch().remainingAttendance());
                    }
                } else {
                    HourlyRunResult result = runHourlyProject(setting, now.toInstant());
                    log.info("hourly project sync completed proCode={} collected={} matched={} "
                                    + "dependenciesReady={} remainingAttendance={}",
                            result.proCode(), result.collection().inserted(), result.matching().matched(),
                            result.dispatch().dependenciesReady(), result.dispatch().remainingAttendance());
                }
            } catch (Exception exception) {
                log.error("hourly project sync failed proCode={} slot={} reason={}",
                        setting.getProject().getProCode(), slot, exception.getMessage(), exception);
            }
        });
    }

    public HourlyRunResult runHourlyProject(ProjectSyncSetting setting, Instant windowEnd) {
        String proCode = setting.getProject().getProCode();
        HikCollectionService.ProjectCollectionResult collection = collect(setting, proCode);
        PersonMatchingService.BatchMatchResult matching = matchingService.rematchUnmatched(
                proCode, properties.getWorker().getMaxTasksPerRun());
        taskService.activateQueuedProject(proCode);
        PushTaskService.WindowDispatchResult dispatch = taskService.dispatchProjectWindow(
                proCode, windowEnd.minusSeconds(3600), windowEnd);
        return new HourlyRunResult(proCode, collection, matching, dispatch);
    }

    public SlotRunResult runProjectSlot(ProjectSyncSetting setting, ZonedDateTime slotEnd) {
        String proCode = setting.getProject().getProCode();
        ZonedDateTime slotStart = slotEnd.toLocalDateTime().minusHours(12).atZone(slotEnd.getZone());
        HikCollectionService.ProjectCollectionResult collection = collect(setting, proCode);
        PersonMatchingService.BatchMatchResult matching = matchingService.rematchUnmatched(
                proCode, properties.getWorker().getMaxTasksPerRun());
        taskService.activateQueuedProject(proCode);
        boolean dependenciesReady = taskService.dispatchProjectDependencies(proCode);
        AttendanceCompletionService.CompletionResult completion = dependenciesReady
                ? completionService.completeWindow(setting, slotStart.toInstant(), slotEnd.toInstant())
                : AttendanceCompletionService.CompletionResult.disabled(
                        proCode, slotStart.toInstant(), slotEnd.toInstant());
        PushTaskService.WindowDispatchResult dispatch = taskService.dispatchProjectWindow(
                proCode, slotStart.toInstant(), slotEnd.toInstant());
        return new SlotRunResult(proCode, slotStart.toInstant(), slotEnd.toInstant(),
                collection, matching, completion, dispatch);
    }

    public DailyRunResult runProject(ProjectSyncSetting setting) {
        String proCode = setting.getProject().getProCode();
        HikCollectionService.ProjectCollectionResult collection = collect(setting, proCode);
        PersonMatchingService.BatchMatchResult matching = matchingService.rematchUnmatched(
                proCode, properties.getWorker().getMaxTasksPerRun());
        taskService.refreshStartedProject(proCode);
        taskService.dispatchProject(proCode);
        return new DailyRunResult(proCode, collection, matching, taskService.projectExecutionResult(proCode));
    }

    static ZonedDateTime nextDueSlot(ProjectSyncSetting setting, ZonedDateTime now) {
        ZonedDateTime latest = latestFinalizableSlot(now);
        if (setting.getLastPushSlotAt() == null) return latest;
        ZonedDateTime previous = setting.getLastPushSlotAt().atZone(now.getZone());
        LocalDateTime nextLocal = previous.toLocalDateTime().plusHours(12);
        ZonedDateTime next = nextLocal.atZone(now.getZone());
        return next.isAfter(latest) ? null : next;
    }

    static ZonedDateTime latestDueSlot(ZonedDateTime now) {
        LocalTime slotTime = now.toLocalTime().isBefore(LocalTime.NOON) ? LocalTime.MIDNIGHT : LocalTime.NOON;
        return now.toLocalDate().atTime(slotTime).atZone(now.getZone());
    }

    static ZonedDateTime latestFinalizableSlot(ZonedDateTime now) {
        return latestDueSlot(now.plusMinutes(20));
    }

    private HikCollectionService.ProjectCollectionResult collect(ProjectSyncSetting setting, String proCode) {
        return setting.isHikCollectionEnabled()
                ? collectionService.collectProject(proCode)
                : new HikCollectionService.ProjectCollectionResult(proCode, 0, 0, 0, 0, 0, 0, 0);
    }

    private void markCompleted(Long settingId, ZonedDateTime slot) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                settingRepository.findById(settingId).ifPresent(current -> {
                    current.setLastPushDate(slot.toLocalDate());
                    current.setLastPushSlotAt(slot.toInstant());
                    settingRepository.save(current);
                }));
    }

    public record DailyRunResult(String proCode,
                                 HikCollectionService.ProjectCollectionResult collection,
                                 PersonMatchingService.BatchMatchResult matching,
                                 PushTaskService.ProjectExecutionResult execution) {
    }

    public record SlotRunResult(String proCode, Instant windowStart, Instant windowEnd,
                                HikCollectionService.ProjectCollectionResult collection,
                                PersonMatchingService.BatchMatchResult matching,
                                AttendanceCompletionService.CompletionResult completion,
                                PushTaskService.WindowDispatchResult dispatch) {
    }

    public record HourlyRunResult(String proCode,
                                  HikCollectionService.ProjectCollectionResult collection,
                                  PersonMatchingService.BatchMatchResult matching,
                                  PushTaskService.WindowDispatchResult dispatch) {
    }
}
