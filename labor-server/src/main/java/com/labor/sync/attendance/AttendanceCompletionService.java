package com.labor.sync.attendance;

import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.hik.AttendanceSource;
import com.labor.sync.hik.HikAttendanceEvent;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchMethod;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.push.PushTaskService;
import com.labor.sync.security.SecurityUtils;
import com.labor.sync.workspace.ProjectSyncSetting;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttendanceCompletionService {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int AUTOMATIC_RATE_JITTER_BASIS_POINTS = 100;

    private final AttendanceBaselineService baselineService;
    private final AttendanceCompletionRunRepository runRepository;
    private final HikAttendanceEventRepository eventRepository;
    private final ProjectRepository projectRepository;
    private final PushTaskService taskService;
    private final CryptoService cryptoService;

    @Transactional
    public CompletionResult completeWindow(ProjectSyncSetting setting, Instant windowStart, Instant windowEnd) {
        String proCode = setting.getProject().getProCode();
        validateWindow(windowStart, windowEnd);
        if (!setting.isAttendanceCompletionEnabled()) {
            return CompletionResult.disabled(proCode, windowStart, windowEnd);
        }
        BigDecimal targetRate = runRepository.findByProCodeAndWindowStartAtAndWindowEndAt(
                        proCode, windowStart, windowEnd)
                .map(AttendanceCompletionRun::getTargetRate)
                .orElseGet(() -> automaticTargetRate(setting.getAttendanceCompletenessRate()));
        return complete(setting, windowStart, windowEnd, targetRate);
    }

    @Transactional
    public CompletionResult completeWindowManually(ProjectSyncSetting setting, Instant windowStart,
                                                   Instant windowEnd, BigDecimal rate) {
        validateWindow(windowStart, windowEnd);
        return complete(setting, windowStart, windowEnd, rate);
    }

    @Transactional(readOnly = true)
    public CompletionPreview previewWindow(ProjectSyncSetting setting, Instant windowStart,
                                           Instant windowEnd, BigDecimal rate) {
        validateWindow(windowStart, windowEnd);
        validateRate(rate);
        CompletionSnapshot snapshot = snapshot(setting, windowStart, windowEnd);
        int requestedTarget = targetCount(rate, snapshot.baseline().size());
        AttendanceCompletionRun existing = runRepository
                .findByProCodeAndWindowStartAtAndWindowEndAt(
                        setting.getProject().getProCode(), windowStart, windowEnd).orElse(null);
        int effectiveTarget = existing == null ? requestedTarget
                : Math.max(requestedTarget, existing.getTargetCount());
        int needed = Math.min(snapshot.missing().size(),
                Math.max(0, effectiveTarget - snapshot.covered().size()));
        return new CompletionPreview(windowStart, windowEnd, rate.setScale(2, RoundingMode.HALF_UP),
                snapshot.baseline().size(), snapshot.captured().size(), snapshot.completed().size(),
                snapshot.covered().size(), effectiveTarget, needed, existing != null,
                existing == null ? null : existing.getId());
    }

    private CompletionResult complete(ProjectSyncSetting setting, Instant windowStart,
                                      Instant windowEnd, BigDecimal rate) {
        validateRate(rate);
        String proCode = setting.getProject().getProCode();
        projectRepository.findByProCodeForUpdate(proCode)
                .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
        CompletionSnapshot snapshot = snapshot(setting, windowStart, windowEnd);
        AttendanceCompletionRun run = runRepository
                .findByProCodeAndWindowStartAtAndWindowEndAt(proCode, windowStart, windowEnd)
                .orElse(null);
        int requestedTarget = targetCount(rate, snapshot.baseline().size());
        int effectiveTarget = run == null ? requestedTarget : Math.max(requestedTarget, run.getTargetCount());
        List<AttendanceBaselineService.BaselinePerson> missing = new ArrayList<>(snapshot.missing());
        Collections.shuffle(missing, RANDOM);
        int needed = Math.min(missing.size(), Math.max(0, effectiveTarget - snapshot.covered().size()));

        if (run == null) {
            run = new AttendanceCompletionRun();
            run.setProCode(proCode);
            run.setWindowStartAt(windowStart);
            run.setWindowEndAt(windowEnd);
            run.setGeneratedBy(SecurityUtils.currentUsername());
            run.setGeneratedCount(0);
        }
        BigDecimal effectiveRate = run.getTargetRate() == null || rate.compareTo(run.getTargetRate()) > 0
                ? rate : run.getTargetRate();
        run.setTargetRate(effectiveRate.setScale(2, RoundingMode.HALF_UP));
        run.setExpectedCount(snapshot.baseline().size());
        run.setCapturedCount(snapshot.captured().size());
        run.setExistingCompletedCount(snapshot.completed().size());
        run.setTargetCount(effectiveTarget);
        runRepository.saveAndFlush(run);

        for (int index = 0; index < needed; index++) {
            HikAttendanceEvent event = generatedEvent(run, missing.get(index), settingZone(setting),
                    windowStart, windowEnd);
            eventRepository.saveAndFlush(event);
            taskService.ensureAttendanceTask(event);
        }
        run.setGeneratedCount(snapshot.completed().size() + needed);
        runRepository.save(run);
        return CompletionResult.from(run, needed);
    }

    private CompletionSnapshot snapshot(ProjectSyncSetting setting, Instant windowStart, Instant windowEnd) {
        String proCode = setting.getProject().getProCode();
        ZoneId zone = zoneId(setting.getZoneId());
        LocalDate businessDate = windowStart.atZone(zone).toLocalDate();
        List<AttendanceBaselineService.BaselinePerson> baseline = new ArrayList<>(
                baselineService.eligiblePeople(proCode, businessDate));
        List<HikAttendanceEventRepository.AttendanceStatisticsEvent> events =
                eventRepository.findStatisticsEvents(proCode, MatchStatus.MATCHED, windowStart, windowEnd);

        Set<Long> baselineIds = baseline.stream().map(
                AttendanceBaselineService.BaselinePerson::personId).collect(java.util.stream.Collectors.toSet());
        Set<Long> captured = personIds(events, AttendanceSource.HIKVISION);
        Set<Long> completed = personIds(events, AttendanceSource.AUTO_COMPLETED);
        captured.retainAll(baselineIds);
        completed.retainAll(baselineIds);
        Set<Long> covered = new HashSet<>(captured);
        covered.addAll(completed);
        List<AttendanceBaselineService.BaselinePerson> missing = baseline.stream()
                .filter(person -> !covered.contains(person.personId())).collect(java.util.stream.Collectors.toList());
        return new CompletionSnapshot(baseline, captured, completed, covered, missing);
    }

    private HikAttendanceEvent generatedEvent(AttendanceCompletionRun run,
                                               AttendanceBaselineService.BaselinePerson person,
                                               ZoneId zone, Instant windowStart, Instant windowEnd) {
        boolean morningWindow = LocalTime.NOON.equals(windowEnd.atZone(zone).toLocalTime());
        HikAttendanceEvent event = new HikAttendanceEvent();
        event.setEventId("AUTO:" + run.getId() + ":" + person.personId());
        event.setProCode(run.getProCode());
        event.setHikPersonId(person.hikPersonId());
        event.setPersonName(person.personName());
        event.setEventTime(randomEventTime(zone, windowStart, windowEnd, morningWindow));
        event.setDirection(morningWindow ? "JINCHANG_JINCHU" : "TUICHANG_JINCHU");
        event.setCheckType("ZHENGCHANG_KAOQINLEIBIE");
        event.setCheckWay("OTHER_FANGSHI");
        event.setCheckLocation("系统按半日完整率 " + run.getTargetRate().toPlainString() + "% 自动补全");
        event.setRawPayloadEncrypted(cryptoService.encrypt(
                "{\"source\":\"AUTO_COMPLETED\",\"completionRunId\":" + run.getId()
                        + ",\"targetRate\":" + run.getTargetRate().toPlainString() + "}"));
        event.setAttendanceSource(AttendanceSource.AUTO_COMPLETED);
        event.setCompletionRun(run);
        event.setMatchStatus(MatchStatus.MATCHED);
        event.setMatchMethod(MatchMethod.AUTO_COMPLETED);
        event.setMatchedPersonId(person.personId());
        event.setMatchReason("系统按半日完整率 " + run.getTargetRate().toPlainString()
                + "% 自动补全；非海康设备采集记录");
        event.setMatchVersion(1);
        event.setReceivedAt(Instant.now());
        return event;
    }

    private Instant randomEventTime(ZoneId zone, Instant windowStart, Instant windowEnd,
                                    boolean morningWindow) {
        LocalDate date = windowStart.atZone(zone).toLocalDate();
        LocalTime preferredStartTime = morningWindow ? LocalTime.of(7, 30) : LocalTime.of(17, 30);
        LocalTime preferredEndTime = morningWindow ? LocalTime.of(9, 30) : LocalTime.of(19, 30);
        Instant preferredStart = date.atTime(preferredStartTime).atZone(zone).toInstant();
        Instant preferredEnd = date.atTime(preferredEndTime).atZone(zone).toInstant();
        Instant rangeStart = preferredStart.isAfter(windowStart) ? preferredStart : windowStart;
        Instant rangeEnd = preferredEnd.isBefore(windowEnd) ? preferredEnd : windowEnd;
        if (!rangeStart.isBefore(rangeEnd)) {
            rangeStart = windowStart;
            rangeEnd = windowEnd;
        }
        long milliseconds = Math.max(1L, rangeEnd.toEpochMilli() - rangeStart.toEpochMilli());
        return rangeStart.plusMillis(Math.floorMod(RANDOM.nextLong(), milliseconds));
    }

    private Set<Long> personIds(List<HikAttendanceEventRepository.AttendanceStatisticsEvent> events,
                                AttendanceSource source) {
        Set<Long> result = new HashSet<>();
        events.stream().filter(event -> event.getAttendanceSource() == source)
                .map(HikAttendanceEventRepository.AttendanceStatisticsEvent::getMatchedPersonId)
                .filter(java.util.Objects::nonNull).forEach(result::add);
        return result;
    }

    private ZoneId zoneId(String value) {
        try {
            return ZoneId.of(value == null || value.isBlank() ? DEFAULT_ZONE.getId() : value);
        } catch (DateTimeException exception) {
            return DEFAULT_ZONE;
        }
    }

    private ZoneId settingZone(ProjectSyncSetting setting) {
        return zoneId(setting.getZoneId());
    }

    private void validateWindow(Instant windowStart, Instant windowEnd) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            throw new BusinessException("ATTENDANCE_COMPLETION_WINDOW_INVALID", "考勤补全窗口无效");
        }
    }

    private void validateRate(BigDecimal rate) {
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0
                || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("ATTENDANCE_COMPLETION_RATE_INVALID", "考勤完整率必须在0到100之间");
        }
    }

    private int targetCount(BigDecimal rate, int baselineCount) {
        return rate.multiply(BigDecimal.valueOf(baselineCount))
                .divide(new BigDecimal("100"), 0, RoundingMode.CEILING).intValueExact();
    }

    BigDecimal automaticTargetRate(BigDecimal configuredRate) {
        validateRate(configuredRate);
        int jitterBasisPoints = RANDOM.nextInt(AUTOMATIC_RATE_JITTER_BASIS_POINTS * 2 + 1)
                - AUTOMATIC_RATE_JITTER_BASIS_POINTS;
        BigDecimal randomized = configuredRate.add(BigDecimal.valueOf(jitterBasisPoints, 2));
        if (randomized.compareTo(BigDecimal.ZERO) < 0) randomized = BigDecimal.ZERO;
        if (randomized.compareTo(new BigDecimal("100")) > 0) randomized = new BigDecimal("100");
        return randomized.setScale(2, RoundingMode.HALF_UP);
    }

    private record CompletionSnapshot(List<AttendanceBaselineService.BaselinePerson> baseline,
                                      Set<Long> captured, Set<Long> completed, Set<Long> covered,
                                      List<AttendanceBaselineService.BaselinePerson> missing) {
    }

    public record CompletionPreview(Instant windowStart, Instant windowEnd, BigDecimal targetRate,
                                    int expectedCount, int capturedCount, int existingCompletedCount,
                                    int coveredCount, int targetCount, int toGenerateCount,
                                    boolean alreadyCompleted, Long runId) {
    }

    public record CompletionResult(String proCode, Instant windowStart, Instant windowEnd,
                                   boolean enabled, Long runId, BigDecimal targetRate,
                                   int expectedCount, int capturedCount, int existingCompletedCount,
                                   int targetCount, int generatedCount, int newlyGeneratedCount) {
        public static CompletionResult disabled(String proCode, Instant start, Instant end) {
            return new CompletionResult(proCode, start, end, false, null, null,
                    0, 0, 0, 0, 0, 0);
        }

        static CompletionResult from(AttendanceCompletionRun run, int newlyGeneratedCount) {
            return new CompletionResult(run.getProCode(), run.getWindowStartAt(), run.getWindowEndAt(), true,
                    run.getId(), run.getTargetRate(), run.getExpectedCount(), run.getCapturedCount(),
                    run.getExistingCompletedCount(), run.getTargetCount(), run.getGeneratedCount(),
                    newlyGeneratedCount);
        }
    }
}
