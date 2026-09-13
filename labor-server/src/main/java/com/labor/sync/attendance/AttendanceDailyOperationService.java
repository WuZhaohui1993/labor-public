package com.labor.sync.attendance;

import com.labor.sync.common.BusinessException;
import com.labor.sync.hik.HikCollectionService;
import com.labor.sync.integration.IntegrationProperties;
import com.labor.sync.matching.PersonMatchingService;
import com.labor.sync.push.PushTaskRepository;
import com.labor.sync.push.PushTaskService;
import com.labor.sync.push.PushTaskStatus;
import com.labor.sync.push.PushTaskType;
import com.labor.sync.workspace.ProjectSyncSetting;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AttendanceDailyOperationService {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final ProjectSyncSettingRepository settingRepository;
    private final AttendanceCompletionService completionService;
    private final HikCollectionService collectionService;
    private final PersonMatchingService matchingService;
    private final PushTaskService taskService;
    private final PushTaskRepository taskRepository;
    private final IntegrationProperties properties;

    public DailyCompletionPreview preview(String proCode, LocalDate date, BigDecimal requestedRate) {
        ProjectSyncSetting setting = setting(proCode);
        BigDecimal rate = requestedRate == null ? setting.getAttendanceCompletenessRate() : requestedRate;
        ZoneId zone = zoneId(setting.getZoneId());
        ZonedDateTime now = ZonedDateTime.now(zone);
        List<DayWindow> windows = dayWindows(date, zone, now);
        List<WindowPreview> previews = windows.stream()
                .map(window -> windowPreview(setting, window, rate)).toList();
        int closedCount = (int) windows.stream().filter(DayWindow::closed).count();
        int earlyCount = (int) windows.stream().filter(DayWindow::beforePushCutoff).count();
        return new DailyCompletionPreview(date, rate, closedCount > 0, closedCount, message(closedCount, earlyCount), previews);
    }

    public DailyCompletionResult completeAndPush(String proCode, LocalDate date, BigDecimal requestedRate) {
        ProjectSyncSetting setting = setting(proCode);
        if (!setting.isSyncStarted()) {
            throw new BusinessException("PROJECT_SYNC_NOT_STARTED", "项目尚未启动同步，不能补全并推送考勤");
        }
        BigDecimal rate = requestedRate == null ? setting.getAttendanceCompletenessRate() : requestedRate;
        ZoneId zone = zoneId(setting.getZoneId());
        List<DayWindow> closedWindows = dayWindows(date, zone, ZonedDateTime.now(zone)).stream()
                .filter(DayWindow::closed).toList();
        if (closedWindows.isEmpty()) {
            throw new BusinessException("ATTENDANCE_DAY_WINDOW_OPEN", "所选日期尚未到11:40或23:40的补全截止时间");
        }

        Instant collectStart = closedWindows.get(0).start();
        Instant collectEnd = closedWindows.get(closedWindows.size() - 1).end();
        HikCollectionService.ProjectCollectionResult collection =
                collectionService.collectProjectRange(proCode, collectStart, collectEnd);
        if (collection.mappings() == 0) {
            throw new BusinessException("HIK_MAPPING_REQUIRED", "当前项目没有启用的海康组织映射，不能安全补全考勤");
        }
        PersonMatchingService.BatchMatchResult matching = matchingService.rematchUnmatched(
                proCode, properties.getWorker().getMaxTasksPerRun());
        taskService.activateQueuedProject(proCode);
        if (!taskService.dispatchProjectDependencies(proCode)) {
            throw new BusinessException("PUSH_DEPENDENCIES_NOT_READY", "基础信息仍有未完成任务，本次未生成补全考勤");
        }

        List<WindowExecution> executions = new ArrayList<>();
        for (DayWindow window : closedWindows) {
            AttendanceCompletionService.CompletionResult completion = completionService.completeWindowManually(
                    setting, window.start(), window.end(), rate);
            PushTaskService.WindowDispatchResult dispatch = taskService.dispatchExactAttendanceWindow(
                    proCode, window.start(), window.end());
            List<com.labor.sync.push.PushTask> tasks = taskRepository
                    .findByProCodeAndTaskTypeAndBusinessAtGreaterThanEqualAndBusinessAtLessThanAndReplayJobIdIsNull(
                            proCode, PushTaskType.ATTENDANCE, window.start(), window.end());
            long succeeded = tasks.stream().filter(task -> task.getStatus() == PushTaskStatus.SUCCESS).count();
            long paused = tasks.stream().filter(task -> task.getStatus() == PushTaskStatus.PAUSED).count();
            executions.add(new WindowExecution(window.label(), window.start(), window.end(),
                    completion.expectedCount(), completion.capturedCount(), completion.generatedCount(),
                    completion.newlyGeneratedCount(), completion.targetCount(), succeeded, paused,
                    dispatch.remainingAttendance(), dispatch.completed()));
        }
        return new DailyCompletionResult(date, rate, collection, matching,
                executions.stream().mapToInt(WindowExecution::newlyGeneratedCount).sum(), executions);
    }

    private WindowPreview windowPreview(ProjectSyncSetting setting, DayWindow window, BigDecimal rate) {
        AttendanceCompletionService.CompletionPreview preview = completionService.previewWindow(
                setting, window.start(), window.end(), rate);
        return new WindowPreview(window.label(), window.start(), window.end(), window.closed(),
                window.beforePushCutoff(),
                preview.alreadyCompleted(), preview.expectedCount(), preview.capturedCount(),
                preview.existingCompletedCount(), preview.coveredCount(), preview.targetCount(),
                preview.toGenerateCount());
    }

    private ProjectSyncSetting setting(String proCode) {
        return settingRepository.findByProjectProCode(proCode)
                .orElseThrow(() -> new BusinessException("WORKSPACE_SETTING_NOT_FOUND", "项目同步设置不存在"));
    }

    static List<DayWindow> dayWindows(LocalDate date, ZoneId zone, ZonedDateTime now) {
        if (date == null) throw new BusinessException("ATTENDANCE_DATE_REQUIRED", "请选择补全日期");
        ZonedDateTime morningStart = date.atStartOfDay(zone);
        ZonedDateTime noon = date.atTime(LocalTime.NOON).atZone(zone);
        ZonedDateTime dayEnd = date.plusDays(1).atStartOfDay(zone);
        ZonedDateTime morningCutoff = date.atTime(11, 40).atZone(zone);
        ZonedDateTime afternoonCutoff = date.atTime(23, 40).atZone(zone);
        return List.of(
                new DayWindow("上午 00:00—12:00", morningStart.toInstant(), noon.toInstant(),
                        !now.isBefore(morningStart), now.isBefore(morningCutoff)),
                new DayWindow("下午 12:00—24:00", noon.toInstant(), dayEnd.toInstant(),
                        !now.isBefore(noon), now.isBefore(afternoonCutoff))
        );
    }

    private String message(int closedCount, int earlyCount) {
        return switch (closedCount) {
            case 0 -> "所选日期尚未进入可补全的半日时段";
            case 1 -> earlyCount > 0
                    ? "当前半日已可补全，但尚未到11:40或23:40推送截止时间；确认后可提前补全并立即推送"
                    : "当前半日已可补全并立即推送";
            default -> earlyCount > 0
                    ? "当前半日已可补全，其中部分尚未到11:40或23:40推送截止时间；确认后可提前补全并立即推送"
                    : "将按上午、下午两个半日窗口补全并立即推送";
        };
    }

    private ZoneId zoneId(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? DEFAULT_ZONE.getId() : value);
        } catch (DateTimeException exception) {
            return DEFAULT_ZONE;
        }
    }

    record DayWindow(String label, Instant start, Instant end, boolean closed, boolean beforePushCutoff) {
    }

    public record DailyCompletionPreview(LocalDate date, BigDecimal targetRate, boolean executable,
                                         int closedWindowCount, String message,
                                         List<WindowPreview> windows) {
    }

    public record WindowPreview(String label, Instant windowStart, Instant windowEnd,
                                boolean closed, boolean beforePushCutoff, boolean alreadyCompleted, int expectedCount,
                                int capturedCount, int autoCompletedCount, int coveredCount,
                                int targetCount, int toGenerateCount) {
    }

    public record DailyCompletionResult(LocalDate date, BigDecimal targetRate,
                                        HikCollectionService.ProjectCollectionResult collection,
                                        PersonMatchingService.BatchMatchResult matching,
                                        int newlyGeneratedCount, List<WindowExecution> windows) {
    }

    public record WindowExecution(String label, Instant windowStart, Instant windowEnd,
                                  int expectedCount, int capturedCount, int autoCompletedCount,
                                  int newlyGeneratedCount, int targetCount, long succeededTaskCount,
                                  long pausedTaskCount, long remainingTaskCount, boolean completed) {
    }
}
