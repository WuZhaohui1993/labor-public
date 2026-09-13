package com.labor.sync.push;

import com.labor.sync.common.CryptoService;
import com.labor.sync.common.BusinessException;
import com.labor.sync.hik.HikAttendanceEvent;
import com.labor.sync.hik.HikAttendanceEventRepository;
import com.labor.sync.hik.MatchMethod;
import com.labor.sync.hik.MatchStatus;
import com.labor.sync.integration.IntegrationProperties;
import com.labor.sync.masterdata.LaborCompany;
import com.labor.sync.masterdata.LaborPerson;
import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.LaborTeam;
import com.labor.sync.masterdata.MasterDataWriteRequest;
import com.labor.sync.masterdata.MasterDataWriteService;
import com.labor.sync.matching.PersonMatchingService;
import com.labor.sync.workspace.ProjectSyncSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class PushPipelineIntegrationTest {
    private static final String TEST_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlK0AAAAASUVORK5CYII=";
    @Autowired MasterDataWriteService writeService;
    @Autowired PushTaskService taskService;
    @Autowired PushTaskRepository taskRepository;
    @Autowired PushPayloadService payloadService;
    @Autowired IntegrationCallLogRepository callLogRepository;
    @Autowired HikAttendanceEventRepository eventRepository;
    @Autowired PersonMatchingService matchingService;
    @Autowired CryptoService cryptoService;
    @Autowired IntegrationProperties integrationProperties;
    @Autowired ProjectSyncSettingRepository syncSettingRepository;
    @Autowired DailySyncOrchestrator dailySyncOrchestrator;

    @Test
    void masterDataAndAttendanceFlowCreateOrderedIdempotentTasksAndPlaintextLogs() {
        LaborProject project = writeService.createProject(new MasterDataWriteRequest.Project("P-PUSH-001", "推送测试项目", null));
        LaborCompany company = writeService.createCompany(new MasterDataWriteRequest.Company("P-PUSH-001", "91110000123456789X",
                "推送测试企业", "LAOWU_CANJIAN", "Y", LocalDate.of(2026, 7, 1), null,
                "联系人", "SHENFEN_ZHENGJIAN", "110101198001011234", "13800000000", "N", null));
        LaborTeam team = writeService.createTeam(new MasterDataWriteRequest.Team("TEAM-PUSH-001", "P-PUSH-001",
                "91110000123456789X", "CANJIAN_TEAM", "推送测试施工队", LocalDate.of(2026, 7, 1), null,
                "队长", "SHENFEN_ZHENGJIAN", "110101198101011234", "13900000000", null));
        LaborPerson person = writeService.createPerson(new MasterDataWriteRequest.Person("推送测试人员", "SHENFEN_ZHENGJIAN",
                "110101199001011234", LocalDate.of(2015, 1, 1), LocalDate.of(2035, 1, 1), "N",
                "P-PUSH-001", "TEAM-PUSH-001", "LAB_USER_BULIDER", "WORK_TYPE_GJG",
                LocalDate.of(2026, 7, 1), null, "POLITICAL_MEMBER", "EDU_LEVEL_BACHELOR", "MARRIED", "M",
                "北京市公安局", "北京市朝阳区测试地址", LocalDate.of(1990, 1, 1),
                "HAN", "CHN", "110000", TEST_PNG, TEST_PNG, TEST_PNG, false, false, false,
                "13700000000", "N", "HIK-PUSH-001", null));

        taskService.consumeOutbox();
        List<PushTask> masterTasks = taskRepository.findAll(Sort.by("priority"));
        assertThat(masterTasks).extracting(PushTask::getTaskType)
                .containsExactly(PushTaskType.PROJECT, PushTaskType.COMPANY, PushTaskType.TEAM, PushTaskType.PERSON);
        assertThat(masterTasks.get(0).getDependencyKey()).isNull();
        assertThat(masterTasks.get(1).getDependencyKey()).isEqualTo(masterTasks.get(0).getIdempotencyKey());
        assertThat(masterTasks.get(2).getDependencyKey()).isEqualTo(masterTasks.get(1).getIdempotencyKey());
        assertThat(masterTasks.get(3).getDependencyKey()).isEqualTo(masterTasks.get(2).getIdempotencyKey());
        assertThat(masterTasks).allMatch(task -> task.getStatus() == PushTaskStatus.WAITING_CONFIRM);
        assertThatThrownBy(() -> taskService.retry(masterTasks.get(0).getId(), "未启动验证"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("按项目启动");
        assertThat(masterTasks).allMatch(task -> task.getPayloadEncrypted() != null
                && !task.getPayloadEncrypted().contains("110101"));

        assertProjectPayload(masterTasks.get(0), project);
        assertCompanyPayload(masterTasks.get(1), company);
        assertTeamPayload(masterTasks.get(2), team);
        assertPersonPayload(masterTasks.get(3), person);
        integrationProperties.getLaborPlatform().setMinimalPayload(false);
        try {
            Map<String, Object> expanded = payloadService.build(masterTasks.get(3)).payload().get(0);
            assertThat(expanded).containsEntry("politicsStatus", "POLITICAL_MEMBER")
                    .containsEntry("positiveIdcardImage", TEST_PNG);
        } finally {
            integrationProperties.getLaborPlatform().setMinimalPayload(true);
        }

        PushTaskService.StartResult started = taskService.startProject("P-PUSH-001");
        assertThat(started.createdTasks()).isZero();
        assertThat(started.totalTasks()).isEqualTo(4);
        assertThat(started.activatedTasks()).isEqualTo(4);
        masterTasks.forEach(task -> taskService.execute(task.getId()));
        assertThat(taskRepository.findAll()).allMatch(task -> task.getStatus() == PushTaskStatus.SUCCESS);

        HikAttendanceEvent event = event(person);
        eventRepository.save(event);
        HikAttendanceEvent matched = matchingService.autoMatch(event.getId());
        assertThat(matched.getMatchStatus()).isEqualTo(MatchStatus.MATCHED);
        assertThat(matched.getMatchMethod()).isEqualTo(MatchMethod.CERTIFICATE);
        PushTask attendanceTask = taskRepository.findAll().stream()
                .filter(task -> task.getTaskType() == PushTaskType.ATTENDANCE).findFirst().orElseThrow();
        Map<String, Object> attendancePayload = payloadService.build(attendanceTask).payload().get(0);
        assertThat(attendancePayload).containsEntry("dierction", "JINCHANG_JINCHU")
                .containsEntry("checkWay", "FACE_FANGSHI")
                .containsEntry("idcardNumber", "110101199001011234");

        taskService.execute(attendanceTask.getId());
        assertThat(taskRepository.findById(attendanceTask.getId()).orElseThrow().getStatus()).isEqualTo(PushTaskStatus.SUCCESS);
        assertThat(callLogRepository.count()).isEqualTo(5);
        assertThat(callLogRepository.findAll()).anySatisfy(log ->
                assertThat(log.getRequestSummaryJson()).contains("110101199001011234"));

        taskService.consumeOutbox();
        assertThat(taskRepository.count()).isEqualTo(5);
    }

    @Test
    void oneProjectDispatchCompletesAllDependencyStagesAndFutureVersionsAutoQueue() {
        LaborProject project = writeService.createProject(
                new MasterDataWriteRequest.Project("P-ORCHESTRATE-001", "完整编排项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company("P-ORCHESTRATE-001", "91110000999999999X",
                "完整编排企业", "LAOWU_CANJIAN", "Y", null, null,
                null, null, null, null, "N", null));
        writeService.createTeam(new MasterDataWriteRequest.Team("TEAM-ORCHESTRATE-001", "P-ORCHESTRATE-001",
                "91110000999999999X", "CANJIAN_TEAM", "完整编排施工队", null, null,
                null, null, null, null, null));
        LaborPerson person = writeService.createPerson(new MasterDataWriteRequest.Person(
                "完整编排人员", "SHENFEN_ZHENGJIAN", "110101199003074514",
                null, null, "Y", "P-ORCHESTRATE-001", "TEAM-ORCHESTRATE-001",
                "LAB_USER_BULIDER", "WORK_TYPE_GJG", null, null,
                null, null, null, "M", null, null, LocalDate.of(1990, 3, 7),
                null, null, null, null, null, null, false, false, false,
                null, "N", "HIK-ORCHESTRATE-001", null));
        taskService.consumeOutbox();

        HikAttendanceEvent event = event(person);
        event.setEventId("EVENT-ORCHESTRATE-001");
        event.setIdcardEncrypted(cryptoService.encrypt("110101199003074514"));
        event.setIdcardHash(cryptoService.hmac("SHENFEN_ZHENGJIAN:110101199003074514"));
        eventRepository.save(event);
        matchingService.autoMatch(event.getId());

        taskService.startProject("P-ORCHESTRATE-001");
        var setting = syncSettingRepository.findByProjectProCode("P-ORCHESTRATE-001").orElseThrow();
        assertThat(setting.isSyncStarted()).isTrue();
        DailySyncOrchestrator.DailyRunResult dailyRun = dailySyncOrchestrator.runProject(setting);
        assertThat(dailyRun.execution().succeeded()).isEqualTo(5);

        assertThat(taskRepository.findByProCode("P-ORCHESTRATE-001"))
                .extracting(PushTask::getTaskType)
                .containsExactlyInAnyOrder(PushTaskType.PROJECT, PushTaskType.COMPANY, PushTaskType.TEAM,
                        PushTaskType.PERSON, PushTaskType.ATTENDANCE);
        assertThat(taskRepository.findByProCode("P-ORCHESTRATE-001"))
                .allMatch(task -> task.getStatus() == PushTaskStatus.SUCCESS);

        writeService.updateProject(project.getId(),
                new MasterDataWriteRequest.Project("P-ORCHESTRATE-001", "完整编排项目新版本", null));
        taskService.consumeOutbox();
        PushTask latest = taskRepository.findByProCode("P-ORCHESTRATE-001").stream()
                .filter(task -> task.getTaskType() == PushTaskType.PROJECT)
                .max(java.util.Comparator.comparingInt(PushTask::getDataVersionNo)).orElseThrow();
        assertThat(latest.getStatus()).isEqualTo(PushTaskStatus.PENDING);
    }

    @Test
    void projectDispatchDrainsEveryTaskEvenWhenGeneralWorkerLimitIsOne() {
        writeService.createProject(new MasterDataWriteRequest.Project(
                "P-DRAIN-001", "项目全量执行测试", null));
        writeService.createCompany(new MasterDataWriteRequest.Company(
                "P-DRAIN-001", "91110000333333333X", "全量执行企业一", "LAOWU_CANJIAN", "Y",
                null, null, null, null, null, null, "N", null));
        writeService.createCompany(new MasterDataWriteRequest.Company(
                "P-DRAIN-001", "91110000444444444X", "全量执行企业二", "LAOWU_CANJIAN", "Y",
                null, null, null, null, null, null, "N", null));
        taskService.consumeOutbox();
        taskService.startProject("P-DRAIN-001");

        int originalBatchSize = integrationProperties.getWorker().getBatchSize();
        int originalMaxTasks = integrationProperties.getWorker().getMaxTasksPerRun();
        integrationProperties.getWorker().setBatchSize(1);
        integrationProperties.getWorker().setMaxTasksPerRun(1);
        try {
            taskService.dispatchProject("P-DRAIN-001");
        } finally {
            integrationProperties.getWorker().setBatchSize(originalBatchSize);
            integrationProperties.getWorker().setMaxTasksPerRun(originalMaxTasks);
        }

        assertThat(taskRepository.findByProCode("P-DRAIN-001")).hasSize(3)
                .allMatch(task -> task.getStatus() == PushTaskStatus.SUCCESS);
    }

    @Test
    void scheduledWindowSendsOnlyAttendanceBeforeBoundaryAndCarriesUnfinishedTasksForward() {
        writeService.createProject(new MasterDataWriteRequest.Project(
                "P-WINDOW-001", "半日窗口测试项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company(
                "P-WINDOW-001", "91110000666666666X", "半日窗口测试企业", "LAOWU_CANJIAN", "Y",
                null, null, null, null, null, null, "N", null));
        writeService.createTeam(new MasterDataWriteRequest.Team(
                "TEAM-WINDOW-001", "P-WINDOW-001", "91110000666666666X", "CANJIAN_TEAM",
                "半日窗口测试施工队", null, null, null, null, null, null, null));
        LaborPerson person = writeService.createPerson(new MasterDataWriteRequest.Person(
                "半日窗口测试人员", "SHENFEN_ZHENGJIAN", "110101199001011234",
                null, null, "Y", "P-WINDOW-001", "TEAM-WINDOW-001",
                "LAB_USER_BULIDER", "WORK_TYPE_GJG", null, null,
                null, null, null, "M", null, null, LocalDate.of(1990, 1, 1),
                null, null, null, null, null, null, false, false, false,
                null, "N", "HIK-WINDOW-001", null));
        taskService.consumeOutbox();
        taskService.startProject("P-WINDOW-001");

        HikAttendanceEvent morning = event(person);
        morning.setEventId("EVENT-WINDOW-MORNING");
        morning.setEventTime(Instant.parse("2026-07-31T03:00:00Z"));
        eventRepository.save(morning);
        matchingService.autoMatch(morning.getId());
        HikAttendanceEvent afternoon = event(person);
        afternoon.setEventId("EVENT-WINDOW-AFTERNOON");
        afternoon.setEventTime(Instant.parse("2026-07-31T05:00:00Z"));
        eventRepository.save(afternoon);
        matchingService.autoMatch(afternoon.getId());

        PushTaskService.WindowDispatchResult noon = taskService.dispatchProjectWindow(
                "P-WINDOW-001", Instant.parse("2026-07-30T16:00:00Z"),
                Instant.parse("2026-07-31T04:00:00Z"));
        List<PushTask> attendance = taskRepository.findByProCode("P-WINDOW-001").stream()
                .filter(task -> task.getTaskType() == PushTaskType.ATTENDANCE)
                .sorted(java.util.Comparator.comparing(PushTask::getBusinessAt)).toList();

        assertThat(noon.completed()).isTrue();
        assertThat(attendance).hasSize(2);
        assertThat(attendance.get(0).getStatus()).isEqualTo(PushTaskStatus.SUCCESS);
        assertThat(attendance.get(1).getStatus()).isEqualTo(PushTaskStatus.PENDING);

        PushTaskService.WindowDispatchResult midnight = taskService.dispatchProjectWindow(
                "P-WINDOW-001", Instant.parse("2026-07-31T04:00:00Z"),
                Instant.parse("2026-07-31T16:00:00Z"));
        assertThat(midnight.completed()).isTrue();
        assertThat(taskRepository.findById(attendance.get(1).getId()).orElseThrow().getStatus())
                .isEqualTo(PushTaskStatus.SUCCESS);
    }

    @Test
    void companySettingSkipsAndRestoresPersonAndAttendancePushes() {
        writeService.createProject(new MasterDataWriteRequest.Project(
                "P-COMPANY-SWITCH-001", "企业推送开关测试", null));
        LaborCompany company = writeService.createCompany(new MasterDataWriteRequest.Company(
                "P-COMPANY-SWITCH-001", "91110000555555555X", "开关测试企业", "LAOWU_CANJIAN", "Y",
                null, null, null, null, null, null, "N", null));
        writeService.createTeam(new MasterDataWriteRequest.Team(
                "TEAM-COMPANY-SWITCH-001", "P-COMPANY-SWITCH-001", "91110000555555555X",
                "CANJIAN_TEAM", "开关测试施工队", null, null, null, null, null, null, null));
        LaborPerson person = writeService.createPerson(new MasterDataWriteRequest.Person(
                "开关测试人员", "SHENFEN_ZHENGJIAN", "110101199003074536",
                null, null, "Y", "P-COMPANY-SWITCH-001", "TEAM-COMPANY-SWITCH-001",
                "LAB_USER_BULIDER", "WORK_TYPE_GJG", null, null,
                null, null, null, "M", null, null, LocalDate.of(1990, 3, 7),
                null, null, null, null, null, null, false, false, false,
                null, "N", "HIK-COMPANY-SWITCH-001", null));

        company = writeService.updateCompanyPersonPushSetting(company.getId(), false);
        taskService.applyCompanyPersonPushSetting(company);
        taskService.consumeOutbox();
        taskService.startProject("P-COMPANY-SWITCH-001");
        taskService.dispatchProject("P-COMPANY-SWITCH-001");

        List<PushTask> initialTasks = taskRepository.findByProCode("P-COMPANY-SWITCH-001");
        assertThat(initialTasks).filteredOn(task -> task.getTaskType() == PushTaskType.PERSON)
                .allMatch(task -> task.getStatus() == PushTaskStatus.IGNORED)
                .allMatch(task -> task.getManualReason().contains("关闭人员及考勤推送"));
        assertThat(initialTasks).filteredOn(task -> Set.of(
                        PushTaskType.PROJECT, PushTaskType.COMPANY, PushTaskType.TEAM).contains(task.getTaskType()))
                .allMatch(task -> task.getStatus() == PushTaskStatus.SUCCESS);

        HikAttendanceEvent event = event(person);
        event.setEventId("EVENT-COMPANY-SWITCH-001");
        event.setIdcardEncrypted(cryptoService.encrypt("110101199003074536"));
        event.setIdcardHash(cryptoService.hmac("SHENFEN_ZHENGJIAN:110101199003074536"));
        eventRepository.save(event);
        matchingService.autoMatch(event.getId());
        assertThat(taskRepository.findByProCode("P-COMPANY-SWITCH-001"))
                .filteredOn(task -> task.getTaskType() == PushTaskType.ATTENDANCE)
                .allMatch(task -> task.getStatus() == PushTaskStatus.IGNORED);

        company = writeService.updateCompanyPersonPushSetting(company.getId(), true);
        taskService.applyCompanyPersonPushSetting(company);
        taskService.dispatchProject("P-COMPANY-SWITCH-001");

        assertThat(taskRepository.findByProCode("P-COMPANY-SWITCH-001"))
                .allMatch(task -> task.getStatus() == PushTaskStatus.SUCCESS);
    }

    @Test
    void ignoredParentDoesNotUnlockItsChildTask() {
        LaborProject project = writeService.createProject(
                new MasterDataWriteRequest.Project("P-IGNORE-GATE-001", "忽略依赖验证", null));
        writeService.createCompany(new MasterDataWriteRequest.Company("P-IGNORE-GATE-001", "91110000888888888X",
                "忽略依赖企业", "LAOWU_CANJIAN", "Y", null, null,
                null, null, null, null, "N", null));
        taskService.consumeOutbox();
        taskService.startProject("P-IGNORE-GATE-001");
        PushTask projectTask = taskRepository.findByProCode("P-IGNORE-GATE-001").stream()
                .filter(task -> task.getTaskType() == PushTaskType.PROJECT).findFirst().orElseThrow();
        PushTask companyTask = taskRepository.findByProCode("P-IGNORE-GATE-001").stream()
                .filter(task -> task.getTaskType() == PushTaskType.COMPANY).findFirst().orElseThrow();

        taskService.ignore(projectTask.getId(), "上级未确认成功，禁止放行验证");
        taskService.dispatchProject("P-IGNORE-GATE-001");

        assertThat(taskRepository.findById(companyTask.getId()).orElseThrow().getStatus())
                .isEqualTo(PushTaskStatus.PENDING);
    }

    @Test
    void unpublishedOlderOutboxVersionIsIgnoredWhenNewerVersionAlreadyExists() {
        LaborProject project = writeService.createProject(
                new MasterDataWriteRequest.Project("P-SUPERSEDE-001", "初始名称", null));
        writeService.updateProject(project.getId(),
                new MasterDataWriteRequest.Project("P-SUPERSEDE-001", "最新名称", null));

        taskService.consumeOutbox();
        List<PushTask> tasks = taskRepository.findAll(Sort.by("dataVersionNo"));
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).getStatus()).isEqualTo(PushTaskStatus.IGNORED);
        assertThat(tasks.get(0).getManualReason()).contains("第 2 版");
        assertThat(tasks.get(1).getStatus()).isEqualTo(PushTaskStatus.WAITING_CONFIRM);

        taskService.startProject("P-SUPERSEDE-001");
        taskService.execute(tasks.get(1).getId());
        assertThat(taskRepository.findById(tasks.get(1).getId()).orElseThrow().getStatus())
                .isEqualTo(PushTaskStatus.SUCCESS);
        assertThat(callLogRepository.count()).isEqualTo(1);
    }

    @Test
    void pendingOlderTaskIsIgnoredAndItsEncryptedSnapshotDoesNotDrift() {
        LaborProject project = writeService.createProject(
                new MasterDataWriteRequest.Project("P-SNAPSHOT-001", "快照初始名称", null));
        taskService.consumeOutbox();
        PushTask first = taskRepository.findAll().stream()
                .filter(task -> task.getAggregateId().equals(String.valueOf(project.getId())))
                .findFirst().orElseThrow();
        assertThat(cryptoService.decrypt(first.getPayloadEncrypted())).contains("快照初始名称");

        writeService.updateProject(project.getId(),
                new MasterDataWriteRequest.Project("P-SNAPSHOT-001", "快照最新名称", null));
        assertThat(cryptoService.decrypt(first.getPayloadEncrypted())).contains("快照初始名称")
                .doesNotContain("快照最新名称");
        taskService.consumeOutbox();

        List<PushTask> tasks = taskRepository.findAll(Sort.by("dataVersionNo"));
        assertThat(tasks).hasSize(2);
        assertThat(tasks.get(0).getStatus()).isEqualTo(PushTaskStatus.IGNORED);
        assertThat(tasks.get(1).getStatus()).isEqualTo(PushTaskStatus.WAITING_CONFIRM);
        assertThat(cryptoService.decrypt(tasks.get(1).getPayloadEncrypted())).contains("快照最新名称");
    }

    @Test
    void disabledMasterDataTaskCannotRunBeforeExternalRuleIsConfirmed() {
        LaborProject project = writeService.createProject(
                new MasterDataWriteRequest.Project("P-DISABLED-001", "待停用项目", null));
        taskService.consumeOutbox();
        PushTask activeTask = taskRepository.findAll().get(0);
        taskService.startProject("P-DISABLED-001");
        taskService.execute(activeTask.getId());
        writeService.deleteProject(project.getId());
        taskService.consumeOutbox();
        PushTask disabledTask = taskRepository.findAll(Sort.by("dataVersionNo")).get(1);

        assertThat(disabledTask.getStatus()).isEqualTo(PushTaskStatus.PAUSED);
        assertThatThrownBy(() -> taskService.retry(disabledTask.getId(), "误操作验证"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("停用规则尚未确认");
    }

    private void assertProjectPayload(PushTask task, LaborProject project) {
        Map<String, Object> payload = payloadService.build(task).payload().get(0);
        assertThat(payload).containsExactlyInAnyOrderEntriesOf(Map.of(
                "proCode", project.getProCode(), "collCompanyName", project.getProjectName(), "sign", 1));
    }

    private void assertCompanyPayload(PushTask task, LaborCompany company) {
        Map<String, Object> payload = payloadService.build(task).payload().get(0);
        assertThat(payload).containsEntry("collCropCode", company.getCollCropCode())
                .containsEntry("collCompanyName", company.getCompanyName())
                .containsEntry("collCropType", "LAOWU_CANJIAN")
                .containsEntry("isChina", "Y").containsEntry("sign", 2)
                .doesNotContainKeys("entryTime", "exitTime", "linkName", "idcardNumber", "linkMobile",
                        "collCropStatus");
    }

    private void assertTeamPayload(PushTask task, LaborTeam team) {
        Map<String, Object> payload = payloadService.build(task).payload().get(0);
        assertThat(payload).containsEntry("teamId", team.getTeamId()).containsEntry("sign", 3)
                .doesNotContainKeys("entryTime", "exitTime", "teamLeaderName", "teamLeaderIdcardType",
                        "teamLeaderIdcardNumber", "teamLeaderMobile");
    }

    private void assertPersonPayload(PushTask task, LaborPerson person) {
        Map<String, Object> payload = payloadService.build(task).payload().get(0);
        assertThat(payload).containsEntry("name", person.getName()).containsEntry("teamId", person.getTeamId())
                .containsEntry("userType", "LAB_USER_BULIDER").containsEntry("workType", "WORK_TYPE_GJG")
                .containsEntry("idcardNumber", "110101199001011234")
                .containsEntry("idcardForever", "N")
                .containsEntry("idcardEndDate", "2035-01-01")
                .doesNotContainKeys("idcardStartDate", "entryTime", "exitTime", "politicsStatus", "eduLevel",
                        "maritalStatus", "sex", "idcardAddress", "homeAddress", "birthday", "nation",
                        "countryCode", "provinceCode", "positiveIdcardImage", "negativeIdcardImage", "headImage",
                        "mobile", "teamLeaderFlag");
    }

    private HikAttendanceEvent event(LaborPerson person) {
        HikAttendanceEvent event = new HikAttendanceEvent();
        event.setEventId("EVENT-PUSH-001");
        event.setProCode(person.getProCode());
        event.setOrgIndexCode("ORG-PUSH-001");
        event.setHikPersonId("DIFFERENT-HIK-ID");
        event.setPersonName("仅用于核对的姓名");
        event.setIdcardType("SHENFEN_ZHENGJIAN");
        event.setIdcardEncrypted(cryptoService.encrypt("110101199001011234"));
        event.setIdcardHash(cryptoService.hmac("SHENFEN_ZHENGJIAN:110101199001011234"));
        event.setEventTime(Instant.parse("2026-07-26T01:02:03Z"));
        event.setDirection("JINCHANG_JINCHU");
        event.setCheckType("ZHENGCHANG_KAOQINLEIBIE");
        event.setCheckWay("FACE_FANGSHI");
        event.setCheckLocation("测试门禁");
        event.setRawPayloadEncrypted(cryptoService.encrypt("{\"idcardNumber\":\"110101199001011234\"}"));
        return event;
    }
}
