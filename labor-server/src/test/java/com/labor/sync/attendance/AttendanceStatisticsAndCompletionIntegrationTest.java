package com.labor.sync.attendance;

import com.labor.sync.common.CryptoService;
import com.labor.sync.hik.AttendanceSource;
import com.labor.sync.hik.HikAttendanceEvent;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchMethod;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.masterdata.LaborPerson;
import com.labor.sync.masterdata.MasterDataWriteRequest;
import com.labor.sync.masterdata.MasterDataWriteService;
import com.labor.sync.push.PushPayloadService;
import com.labor.sync.push.PushTask;
import com.labor.sync.push.PushTaskRepository;
import com.labor.sync.push.PushTaskService;
import com.labor.sync.push.PushTaskStatus;
import com.labor.sync.push.PushTaskType;
import com.labor.sync.workspace.ProjectSyncSetting;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AttendanceStatisticsAndCompletionIntegrationTest {
    private static final Instant WINDOW_START = Instant.parse("2026-07-30T16:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-07-31T04:00:00Z");

    @Autowired MasterDataWriteService writeService;
    @Autowired PushTaskService taskService;
    @Autowired PushTaskRepository taskRepository;
    @Autowired PushPayloadService payloadService;
    @Autowired HikAttendanceEventRepository eventRepository;
    @Autowired AttendanceStatisticsService statisticsService;
    @Autowired AttendanceCompletionService completionService;
    @Autowired AttendanceCompletionRunRepository completionRunRepository;
    @Autowired ProjectSyncSettingRepository settingRepository;
    @Autowired CryptoService cryptoService;

    @Test
    void dailyStatisticsShowsExpectedAttendedAndMissingPeople() {
        Fixture fixture = fixture("P-STATS-001", 2);
        pushPeople(fixture.proCode());
        saveMatchedEvent(fixture.people().get(0), "EVENT-STATS-001",
                Instant.parse("2026-07-31T00:15:00Z"), AttendanceSource.HIKVISION);

        AttendanceStatisticsService.DailySummary summary = statisticsService.summaries(
                fixture.proCode(), LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 31)).get(0);

        assertThat(summary.expectedCount()).isEqualTo(2);
        assertThat(summary.attendedCount()).isEqualTo(1);
        assertThat(summary.capturedCount()).isEqualTo(1);
        assertThat(summary.autoCompletedCount()).isZero();
        assertThat(summary.missingCount()).isEqualTo(1);
        assertThat(summary.completenessRate()).isEqualTo(50D);

        AttendanceStatisticsService.DailyDetails attended = statisticsService.details(
                fixture.proCode(), LocalDate.of(2026, 7, 31), "ATTENDED", "", 0, 20);
        assertThat(attended.people().items()).singleElement()
                .satisfies(row -> {
                    assertThat(row.personId()).isEqualTo(fixture.people().get(0).getId());
                    assertThat(row.attendanceSource()).isEqualTo("HIKVISION");
                    assertThat(row.capturedEventCount()).isEqualTo(1);
                });

        AttendanceStatisticsService.DailyDetails missing = statisticsService.details(
                fixture.proCode(), LocalDate.of(2026, 7, 31), "MISSING", "", 0, 20);
        assertThat(missing.people().items()).singleElement()
                .satisfies(row -> assertThat(row.personId()).isEqualTo(fixture.people().get(1).getId()));
    }

    @Test
    void completionReachesHalfDayTargetAndIsIdempotent() {
        Fixture fixture = fixture("P-COMPLETE-001", 4);
        pushPeople(fixture.proCode());
        HikAttendanceEvent captured = saveMatchedEvent(fixture.people().get(0), "EVENT-CAPTURED-001",
                Instant.parse("2026-07-31T00:15:00Z"), AttendanceSource.HIKVISION);
        taskService.ensureAttendanceTask(captured);

        ProjectSyncSetting setting = settingRepository.findByProjectProCode(fixture.proCode()).orElseThrow();
        setting.setAttendanceCompletionEnabled(true);
        setting.setAttendanceCompletenessRate(new BigDecimal("75.00"));
        settingRepository.save(setting);

        AttendanceCompletionService.CompletionResult first = completionService.completeWindow(
                setting, WINDOW_START, WINDOW_END);
        AttendanceCompletionService.CompletionResult second = completionService.completeWindow(
                setting, WINDOW_START, WINDOW_END);

        assertThat(first.expectedCount()).isEqualTo(4);
        assertThat(first.capturedCount()).isEqualTo(1);
        assertThat(first.targetRate()).isBetween(new BigDecimal("74.00"), new BigDecimal("76.00"));
        int expectedTarget = first.targetRate().multiply(new BigDecimal("4"))
                .divide(new BigDecimal("100"), 0, RoundingMode.CEILING).intValueExact();
        assertThat(first.targetCount()).isEqualTo(expectedTarget);
        assertThat(first.generatedCount()).isEqualTo(expectedTarget - 1);
        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.targetRate()).isEqualByComparingTo(first.targetRate());
        assertThat(first.newlyGeneratedCount()).isEqualTo(expectedTarget - 1);
        assertThat(second.newlyGeneratedCount()).isZero();
        assertThat(completionRunRepository.count()).isEqualTo(1);

        List<HikAttendanceEvent> generated = eventRepository.findAll().stream()
                .filter(event -> event.getAttendanceSource() == AttendanceSource.AUTO_COMPLETED).toList();
        assertThat(generated).hasSize(expectedTarget - 1).allSatisfy(event -> {
            assertThat(event.getCompletionRun().getId()).isEqualTo(first.runId());
            assertThat(event.getMatchMethod()).isEqualTo(MatchMethod.AUTO_COMPLETED);
            assertThat(event.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
            assertThat(event.getEventTime()).isBetween(WINDOW_START, WINDOW_END.minusMillis(1));
            assertThat(event.getCheckWay()).isEqualTo("OTHER_FANGSHI");
            assertThat(event.getDirection()).isEqualTo("JINCHANG_JINCHU");
        });

        List<PushTask> generatedTasks = taskRepository.findByProCode(fixture.proCode()).stream()
                .filter(task -> task.getTaskType() == PushTaskType.ATTENDANCE)
                .filter(task -> generated.stream().anyMatch(
                        event -> String.valueOf(event.getId()).equals(task.getAggregateId())))
                .toList();
        assertThat(generatedTasks).hasSize(expectedTarget - 1).allSatisfy(task ->
                assertThat(payloadService.build(task).payload().get(0))
                        .containsEntry("checkWay", "OTHER_FANGSHI"));

        taskService.dispatchProjectWindow(fixture.proCode(), WINDOW_START, WINDOW_END);
        assertThat(generatedTasks).allSatisfy(task ->
                assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                        .isEqualTo(PushTaskStatus.SUCCESS));

        AttendanceStatisticsService.DailySummary summary = statisticsService.summaries(
                fixture.proCode(), LocalDate.of(2026, 7, 31), LocalDate.of(2026, 7, 31)).get(0);
        assertThat(summary.attendedCount()).isEqualTo(expectedTarget);
        assertThat(summary.capturedCount()).isEqualTo(1);
        assertThat(summary.autoCompletedCount()).isEqualTo(expectedTarget - 1);
        assertThat(summary.completenessRate()).isEqualTo(expectedTarget * 25D);

        AttendanceCompletionService.CompletionResult toppedUp = completionService.completeWindowManually(
                setting, WINDOW_START, WINDOW_END, new BigDecimal("100.00"));
        assertThat(toppedUp.runId()).isEqualTo(first.runId());
        assertThat(toppedUp.generatedCount()).isEqualTo(3);
        assertThat(toppedUp.newlyGeneratedCount()).isEqualTo(4 - expectedTarget);
        assertThat(completionRunRepository.count()).isEqualTo(1);
    }

    @Test
    void realHikAttendanceStopsUnsentCompletionTaskInSameHalfDay() {
        Fixture fixture = fixture("P-RECONCILE-001", 2);
        pushPeople(fixture.proCode());
        ProjectSyncSetting setting = settingRepository.findByProjectProCode(fixture.proCode()).orElseThrow();

        AttendanceCompletionService.CompletionResult completion = completionService.completeWindowManually(
                setting, WINDOW_START, WINDOW_END, new BigDecimal("50.00"));
        assertThat(completion.newlyGeneratedCount()).isEqualTo(1);
        HikAttendanceEvent generated = eventRepository.findAll().stream()
                .filter(event -> event.getAttendanceSource() == AttendanceSource.AUTO_COMPLETED)
                .findFirst().orElseThrow();
        LaborPerson person = fixture.people().stream()
                .filter(item -> item.getId().equals(generated.getMatchedPersonId())).findFirst().orElseThrow();

        HikAttendanceEvent hik = saveMatchedEvent(person, "EVENT-REAL-AFTER-COMPLETION",
                generated.getEventTime().plusSeconds(60), AttendanceSource.HIKVISION);
        PushTask hikTask = taskService.ensureAttendanceTask(hik);
        PushTaskService.AttendanceReconciliationResult reconciliation =
                taskService.reconcileHikAttendance(hik);

        assertThat(reconciliation.completionAlreadyExternal()).isFalse();
        assertThat(reconciliation.pausedCompletionTaskCount()).isEqualTo(1);
        assertThat(taskRepository.findById(hikTask.getId()).orElseThrow().getStatus())
                .isEqualTo(PushTaskStatus.PENDING);
        PushTask completionTask = taskRepository
                .findByTaskTypeAndAggregateIdAndReplayJobIdIsNullOrderByCreatedAtDesc(
                        PushTaskType.ATTENDANCE, String.valueOf(generated.getId()))
                .get(0);
        assertThat(completionTask.getStatus()).isEqualTo(PushTaskStatus.PAUSED);
    }

    @Test
    void realHikAttendanceIsRetainedButPausedAfterCompletionWasPushed() {
        Fixture fixture = fixture("P-RECONCILE-PUSHED-001", 1);
        pushPeople(fixture.proCode());
        ProjectSyncSetting setting = settingRepository.findByProjectProCode(fixture.proCode()).orElseThrow();
        AttendanceCompletionService.CompletionResult completion = completionService.completeWindowManually(
                setting, WINDOW_START, WINDOW_END, new BigDecimal("100.00"));
        HikAttendanceEvent generated = eventRepository.findAll().stream()
                .filter(event -> event.getAttendanceSource() == AttendanceSource.AUTO_COMPLETED)
                .findFirst().orElseThrow();
        taskService.dispatchExactAttendanceWindow(fixture.proCode(), WINDOW_START, WINDOW_END);

        HikAttendanceEvent hik = saveMatchedEvent(fixture.people().get(0), "EVENT-REAL-AFTER-PUSH",
                generated.getEventTime().plusSeconds(60), AttendanceSource.HIKVISION);
        PushTask hikTask = taskService.ensureAttendanceTask(hik);
        PushTaskService.AttendanceReconciliationResult reconciliation =
                taskService.reconcileHikAttendance(hik);

        assertThat(completion.newlyGeneratedCount()).isEqualTo(1);
        assertThat(reconciliation.completionAlreadyExternal()).isTrue();
        assertThat(reconciliation.pausedHikTaskCount()).isEqualTo(1);
        assertThat(taskRepository.findById(hikTask.getId()).orElseThrow().getStatus())
                .isEqualTo(PushTaskStatus.PAUSED);
        assertThat(eventRepository.findById(hik.getId())).isPresent();
    }

    private Fixture fixture(String proCode, int personCount) {
        String companyCode = "91" + Math.abs(proCode.hashCode()) + "X";
        String teamId = "TEAM-" + proCode;
        writeService.createProject(new MasterDataWriteRequest.Project(proCode, "考勤测试项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company(
                proCode, companyCode, "考勤测试企业", "LAOWU_CANJIAN", "Y",
                LocalDate.of(2026, 7, 1), null, null, null, null, null, "N", null));
        writeService.createTeam(new MasterDataWriteRequest.Team(
                teamId, proCode, companyCode, "CANJIAN_TEAM", "考勤测试施工队",
                LocalDate.of(2026, 7, 1), null, null, null, null, null, null));
        java.util.ArrayList<LaborPerson> people = new java.util.ArrayList<>();
        for (int index = 1; index <= personCount; index++) {
            String idcard = String.format("11010119900101%04d", index);
            people.add(writeService.createPerson(new MasterDataWriteRequest.Person(
                    "考勤人员" + index, "SHENFEN_ZHENGJIAN", idcard,
                    null, null, "Y", proCode, teamId, "LAB_USER_BULIDER", "WORK_TYPE_GJG",
                    LocalDate.of(2026, 7, 1), null, null, null, null, "M", null, null,
                    LocalDate.of(1990, 1, 1), null, null, null, null, null, null,
                    false, false, false, null, "N", "HIK-" + proCode + "-" + index, null)));
        }
        return new Fixture(proCode, people);
    }

    private void pushPeople(String proCode) {
        taskService.consumeOutbox();
        taskService.startProject(proCode);
        taskService.dispatchProject(proCode);
        assertThat(taskRepository.findByProCode(proCode))
                .filteredOn(task -> task.getTaskType() == PushTaskType.PERSON)
                .allMatch(task -> task.getStatus() == PushTaskStatus.SUCCESS);
    }

    private HikAttendanceEvent saveMatchedEvent(LaborPerson person, String eventId,
                                                Instant eventTime, AttendanceSource source) {
        HikAttendanceEvent event = new HikAttendanceEvent();
        event.setEventId(eventId);
        event.setProCode(person.getProCode());
        event.setHikPersonId(person.getHikPersonId());
        event.setPersonName(person.getName());
        event.setEventTime(eventTime);
        event.setDirection("JINCHANG_JINCHU");
        event.setCheckType("ZHENGCHANG_KAOQINLEIBIE");
        event.setCheckWay("FACE_FANGSHI");
        event.setRawPayloadEncrypted(cryptoService.encrypt("{}"));
        event.setAttendanceSource(source);
        event.setMatchStatus(MatchStatus.MATCHED);
        event.setMatchMethod(MatchMethod.HIK_PERSON_ID);
        event.setMatchedPersonId(person.getId());
        event.setMatchReason("测试匹配");
        event.setMatchVersion(1);
        event.setReceivedAt(Instant.now());
        return eventRepository.saveAndFlush(event);
    }

    private record Fixture(String proCode, List<LaborPerson> people) {
    }
}
