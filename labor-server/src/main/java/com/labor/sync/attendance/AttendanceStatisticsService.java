package com.labor.sync.attendance;

import com.labor.sync.common.BusinessException;
import com.labor.sync.common.PageResponse;
import com.labor.sync.hik.AttendanceSource;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.push.PushTask;
import com.labor.sync.push.PushTaskRepository;
import com.labor.sync.push.PushTaskStatus;
import com.labor.sync.push.PushTaskType;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AttendanceStatisticsService {
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_RANGE_DAYS = 90;

    private final AttendanceBaselineService baselineService;
    private final HikAttendanceEventRepository eventRepository;
    private final PushTaskRepository taskRepository;
    private final ProjectSyncSettingRepository settingRepository;

    @Transactional(readOnly = true)
    public List<DailySummary> summaries(String proCode, LocalDate startDate, LocalDate endDate) {
        validateRange(startDate, endDate);
        ZoneId zone = projectZone(proCode);
        List<AttendanceBaselineService.BaselinePerson> people = baselineService.pushedPeople(proCode);
        Map<LocalDate, List<HikAttendanceEventRepository.AttendanceStatisticsEvent>> eventsByDate =
                eventsByDate(proCode, startDate, endDate, zone);

        List<DailySummary> result = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            List<AttendanceBaselineService.BaselinePerson> expected = baselineService.eligibleOn(people, date);
            Set<Long> expectedIds = expected.stream().map(
                    AttendanceBaselineService.BaselinePerson::personId).collect(Collectors.toSet());
            List<HikAttendanceEventRepository.AttendanceStatisticsEvent> dayEvents =
                    eventsByDate.getOrDefault(date, List.of());
            Set<Long> attended = dayEvents.stream().map(
                            HikAttendanceEventRepository.AttendanceStatisticsEvent::getMatchedPersonId)
                    .filter(expectedIds::contains).collect(Collectors.toSet());
            Set<Long> outside = dayEvents.stream().map(
                            HikAttendanceEventRepository.AttendanceStatisticsEvent::getMatchedPersonId)
                    .filter(Objects::nonNull).filter(personId -> !expectedIds.contains(personId))
                    .collect(Collectors.toSet());
            Set<Long> captured = personIds(dayEvents, AttendanceSource.HIKVISION, expectedIds);
            Set<Long> completed = personIds(dayEvents, AttendanceSource.AUTO_COMPLETED, expectedIds);
            List<HikAttendanceEventRepository.AttendanceStatisticsEvent> expectedEvents = dayEvents.stream()
                    .filter(event -> expectedIds.contains(event.getMatchedPersonId())).toList();
            int expectedCount = expected.size();
            int attendedCount = attended.size();
            result.add(new DailySummary(date, expectedCount, attendedCount, captured.size(), completed.size(),
                    Math.max(0, expectedCount - attendedCount), completeness(expectedCount, attendedCount),
                    expectedEvents.size(), (int) expectedEvents.stream()
                    .filter(event -> event.getAttendanceSource() == AttendanceSource.AUTO_COMPLETED).count(),
                    outside.size()));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public DailyDetails details(String proCode, LocalDate date, String attendanceStatus,
                                String search, int page, int size) {
        if (date == null) throw new BusinessException("ATTENDANCE_DATE_REQUIRED", "请选择统计日期");
        String normalizedStatus = normalizeStatus(attendanceStatus);
        ZoneId zone = projectZone(proCode);
        List<AttendanceBaselineService.BaselinePerson> expected = baselineService.eligiblePeople(proCode, date);
        Map<Long, List<HikAttendanceEventRepository.AttendanceStatisticsEvent>> eventsByPerson =
                statisticsEvents(proCode, date, date, zone).stream()
                        .collect(Collectors.groupingBy(
                                HikAttendanceEventRepository.AttendanceStatisticsEvent::getMatchedPersonId,
                                LinkedHashMap::new, Collectors.toList()));
        Map<Long, PushTaskStatus> latestTaskStatus = latestAttendanceTaskStatus(proCode, date, zone);
        String keyword = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);

        List<PersonAttendanceDetail> all = expected.stream().map(person -> detail(
                        person, eventsByPerson.getOrDefault(person.personId(), List.of()), latestTaskStatus))
                .filter(row -> normalizedStatus == null || normalizedStatus.equals(row.attendanceStatus()))
                .filter(row -> keyword.isBlank() || matches(row, keyword))
                .sorted(Comparator.comparing(PersonAttendanceDetail::companyName)
                        .thenComparing(PersonAttendanceDetail::teamName)
                        .thenComparing(PersonAttendanceDetail::personName)
                        .thenComparing(PersonAttendanceDetail::personId))
                .toList();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 200);
        int from = Math.min(all.size(), safePage * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        DailySummary summary = summarizeDetails(date, expected.size(), eventsByPerson, expected);
        return new DailyDetails(summary, new PageResponse<>(all.subList(from, to), safePage, safeSize, all.size()));
    }

    private DailySummary summarizeDetails(LocalDate date, int expectedCount,
                                          Map<Long, List<HikAttendanceEventRepository.AttendanceStatisticsEvent>> events,
                                          List<AttendanceBaselineService.BaselinePerson> expected) {
        Set<Long> expectedIds = expected.stream().map(
                AttendanceBaselineService.BaselinePerson::personId).collect(Collectors.toSet());
        int attended = (int) expectedIds.stream().filter(events::containsKey).count();
        List<HikAttendanceEventRepository.AttendanceStatisticsEvent> expectedEvents = events.entrySet().stream()
                .filter(entry -> expectedIds.contains(entry.getKey())).flatMap(entry -> entry.getValue().stream())
                .toList();
        Set<Long> captured = personIds(expectedEvents, AttendanceSource.HIKVISION, expectedIds);
        Set<Long> completed = personIds(expectedEvents, AttendanceSource.AUTO_COMPLETED, expectedIds);
        int outside = (int) events.keySet().stream().filter(personId -> !expectedIds.contains(personId)).count();
        return new DailySummary(date, expectedCount, attended, captured.size(), completed.size(),
                Math.max(0, expectedCount - attended), completeness(expectedCount, attended),
                expectedEvents.size(), (int) expectedEvents.stream()
                .filter(event -> event.getAttendanceSource() == AttendanceSource.AUTO_COMPLETED).count(), outside);
    }

    private Set<Long> personIds(List<HikAttendanceEventRepository.AttendanceStatisticsEvent> events,
                                AttendanceSource source, Set<Long> expectedIds) {
        return events.stream().filter(event -> event.getAttendanceSource() == source)
                .map(HikAttendanceEventRepository.AttendanceStatisticsEvent::getMatchedPersonId)
                .filter(expectedIds::contains).collect(Collectors.toSet());
    }

    private Map<LocalDate, List<HikAttendanceEventRepository.AttendanceStatisticsEvent>> eventsByDate(
            String proCode, LocalDate startDate, LocalDate endDate, ZoneId zone) {
        return statisticsEvents(proCode, startDate, endDate, zone).stream()
                .collect(Collectors.groupingBy(event -> event.getEventTime().atZone(zone).toLocalDate(),
                        LinkedHashMap::new, Collectors.toList()));
    }

    private List<HikAttendanceEventRepository.AttendanceStatisticsEvent> statisticsEvents(
            String proCode, LocalDate startDate, LocalDate endDate, ZoneId zone) {
        Instant start = startDate.atStartOfDay(zone).toInstant();
        Instant end = endDate.plusDays(1).atStartOfDay(zone).toInstant();
        return eventRepository.findStatisticsEvents(proCode, MatchStatus.MATCHED, start, end);
    }

    private Map<Long, PushTaskStatus> latestAttendanceTaskStatus(String proCode, LocalDate date, ZoneId zone) {
        Map<Long, PushTask> latestByEvent = new HashMap<>();
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        for (PushTask task : taskRepository
                .findByProCodeAndTaskTypeAndBusinessAtGreaterThanEqualAndBusinessAtLessThanAndReplayJobIdIsNull(
                        proCode, PushTaskType.ATTENDANCE, start, end)) {
            Long eventId = parseId(task.getAggregateId());
            if (eventId == null) continue;
            latestByEvent.merge(eventId, task, BinaryOperator.maxBy(
                    Comparator.comparing(PushTask::getCreatedAt).thenComparing(PushTask::getId)));
        }
        return latestByEvent.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().getStatus()));
    }

    private PersonAttendanceDetail detail(AttendanceBaselineService.BaselinePerson person,
                                          List<HikAttendanceEventRepository.AttendanceStatisticsEvent> events,
                                          Map<Long, PushTaskStatus> latestTaskStatus) {
        List<HikAttendanceEventRepository.AttendanceStatisticsEvent> ordered = events.stream()
                .sorted(Comparator.comparing(HikAttendanceEventRepository.AttendanceStatisticsEvent::getEventTime)
                        .thenComparing(HikAttendanceEventRepository.AttendanceStatisticsEvent::getId)).toList();
        int entryCount = (int) ordered.stream().filter(event -> "JINCHANG_JINCHU".equals(event.getDirection())).count();
        int exitCount = (int) ordered.stream().filter(event -> "TUICHANG_JINCHU".equals(event.getDirection())).count();
        List<String> locations = ordered.stream().map(
                        HikAttendanceEventRepository.AttendanceStatisticsEvent::getCheckLocation)
                .filter(value -> value != null && !value.isBlank()).distinct().limit(5).toList();
        List<PushTaskStatus> statuses = ordered.stream().map(event -> latestTaskStatus.get(event.getId()))
                .filter(Objects::nonNull).toList();
        long capturedEventCount = ordered.stream()
                .filter(event -> event.getAttendanceSource() == AttendanceSource.HIKVISION).count();
        long completedEventCount = ordered.stream()
                .filter(event -> event.getAttendanceSource() == AttendanceSource.AUTO_COMPLETED).count();
        return new PersonAttendanceDetail(person.personId(), person.personName(), person.workType(),
                person.companyCode(), person.companyName(), person.teamId(), person.teamName(),
                ordered.isEmpty() ? "MISSING" : "ATTENDED",
                attendanceSource(capturedEventCount, completedEventCount),
                ordered.isEmpty() ? null : ordered.get(0).getEventTime(),
                ordered.isEmpty() ? null : ordered.get(ordered.size() - 1).getEventTime(),
                ordered.size(), Math.toIntExact(capturedEventCount), Math.toIntExact(completedEventCount),
                entryCount, exitCount, locations, pushStatus(statuses));
    }

    private String attendanceSource(long captured, long completed) {
        if (captured > 0 && completed > 0) return "MIXED";
        if (captured > 0) return AttendanceSource.HIKVISION.name();
        if (completed > 0) return AttendanceSource.AUTO_COMPLETED.name();
        return "NONE";
    }

    private String pushStatus(List<PushTaskStatus> statuses) {
        if (statuses.isEmpty()) return "NOT_CREATED";
        if (statuses.stream().allMatch(status -> status == PushTaskStatus.SUCCESS)) return "SUCCESS";
        if (statuses.stream().anyMatch(status -> Set.of(PushTaskStatus.FAILED, PushTaskStatus.PAUSED)
                .contains(status))) return "ATTENTION";
        if (statuses.stream().anyMatch(status -> status == PushTaskStatus.RUNNING)) return "RUNNING";
        if (statuses.stream().anyMatch(status -> Set.of(PushTaskStatus.PENDING, PushTaskStatus.WAITING_CONFIRM)
                .contains(status))) return "PENDING";
        if (statuses.stream().allMatch(status -> status == PushTaskStatus.IGNORED)) return "IGNORED";
        return "PARTIAL";
    }

    private boolean matches(PersonAttendanceDetail row, String keyword) {
        return contains(row.personName(), keyword) || contains(row.teamName(), keyword)
                || contains(row.companyName(), keyword) || contains(row.workType(), keyword);
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ATTENDED", "MISSING").contains(normalized)) {
            throw new BusinessException("ATTENDANCE_STATUS_INVALID", "考勤状态筛选值无效");
        }
        return normalized;
    }

    private void validateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new BusinessException("ATTENDANCE_DATE_REQUIRED", "请选择统计开始和结束日期");
        }
        if (endDate.isBefore(startDate)) {
            throw new BusinessException("ATTENDANCE_DATE_RANGE_INVALID", "统计结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException("ATTENDANCE_DATE_RANGE_TOO_LARGE", "单次最多统计90天");
        }
    }

    private ZoneId projectZone(String proCode) {
        String value = settingRepository.findByProjectProCode(proCode)
                .map(setting -> setting.getZoneId()).orElse(DEFAULT_ZONE.getId());
        try {
            return ZoneId.of(value);
        } catch (DateTimeException exception) {
            return DEFAULT_ZONE;
        }
    }

    private Long parseId(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private double completeness(int expected, int attended) {
        if (expected == 0) return 0D;
        return Math.round(attended * 10_000D / expected) / 100D;
    }

    public record DailySummary(LocalDate date, int expectedCount, int attendedCount,
                               int capturedCount, int autoCompletedCount, int missingCount,
                               double completenessRate, int eventCount, int autoCompletedEventCount,
                               int outsideBaselineCount) {
    }

    public record PersonAttendanceDetail(Long personId, String personName, String workType,
                                         String companyCode, String companyName, String teamId, String teamName,
                                         String attendanceStatus, String attendanceSource,
                                         Instant firstEventTime, Instant lastEventTime,
                                         int eventCount, int capturedEventCount, int autoCompletedEventCount,
                                         int entryCount, int exitCount,
                                         List<String> locations, String pushStatus) {
    }

    public record DailyDetails(DailySummary summary, PageResponse<PersonAttendanceDetail> people) {
    }
}
