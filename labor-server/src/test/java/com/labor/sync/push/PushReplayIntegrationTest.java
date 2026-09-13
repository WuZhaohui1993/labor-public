package com.labor.sync.push;

import com.labor.sync.masterdata.MasterDataWriteRequest;
import com.labor.sync.masterdata.MasterDataWriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PushReplayIntegrationTest {
    @Autowired MasterDataWriteService writeService;
    @Autowired PushTaskService taskService;
    @Autowired PushReplayService replayService;
    @Autowired PushTaskRepository taskRepository;
    @Autowired PushReplayJobRepository replayJobRepository;
    @Autowired PushReplayItemRepository replayItemRepository;
    @Autowired IntegrationCallLogRepository callLogRepository;

    @Test
    void successfulTaskIsReplayedFromSnapshotWithoutChangingOriginalTask() {
        writeService.createProject(new MasterDataWriteRequest.Project(
                "P-REPLAY-001", "补推测试项目", null));
        taskService.rebuildProject("P-REPLAY-001");
        taskService.startProject("P-REPLAY-001");
        taskService.dispatchProject("P-REPLAY-001");

        PushTask original = taskRepository.findByProCodeAndReplayJobIdIsNull("P-REPLAY-001").stream()
                .filter(task -> task.getTaskType() == PushTaskType.PROJECT)
                .findFirst().orElseThrow();
        assertThat(original.getStatus()).isEqualTo(PushTaskStatus.SUCCESS);
        String originalPayload = original.getPayloadEncrypted();

        PushReplayService.ReplayJobView created = replayService.createAndStart(
                "P-REPLAY-001",
                new PushReplayService.ReplaySelection(false, "P-REPLAY-001", List.of(original.getId()), null,
                        null, null, null),
                "接收端数据丢失验证");
        assertThat(created.status()).isEqualTo(PushReplayJobStatus.CREATED);
        assertThat(created.warning()).contains("后台正在准备");
        assertThat(replayJobRepository.findById(created.id()).orElseThrow().getSelectionJson())
                .contains("P-REPLAY-001");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(replayService.job("P-REPLAY-001", created.id()).status())
                        .isEqualTo(PushReplayJobStatus.SUCCESS));

        PushTask unchanged = taskRepository.findById(original.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(PushTaskStatus.SUCCESS);
        assertThat(unchanged.getPayloadEncrypted()).isEqualTo(originalPayload);

        PushReplayItem item = replayItemRepository.findByJobIdOrderByTaskTypeAscIdAsc(created.id()).get(0);
        PushTask replayTask = taskRepository.findById(item.getReplayTaskId()).orElseThrow();
        assertThat(replayTask.getStatus()).isEqualTo(PushTaskStatus.SUCCESS);
        assertThat(replayTask.getReplaySourceTaskId()).isEqualTo(original.getId());
        assertThat(replayTask.getPayloadEncrypted()).isEqualTo(originalPayload);
        assertThat(item.getStatus()).isEqualTo(PushReplayItemStatus.SUCCESS);
        assertThat(callLogRepository.findAll()).anySatisfy(call -> {
            assertThat(call.getReplayJobId()).isEqualTo(created.id());
            assertThat(call.getBatchSize()).isEqualTo(1);
            assertThat(call.getRequestSummaryJson()).contains("P-REPLAY-001");
        });
    }

    @Test
    void previewFiltersLatestSuccessfulTasksByTypeCompanyAndBusinessDate() {
        String proCode = "P-REPLAY-FILTER-001";
        String companyCode = "91110000987654321X";
        writeService.createProject(new MasterDataWriteRequest.Project(proCode, "补推筛选项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company(proCode, companyCode, "补推筛选企业",
                "LAOWU_CANJIAN", "Y", null, null, null, null, null, null, "N", null));
        taskService.rebuildProject(proCode);
        taskService.startProject(proCode);
        taskService.dispatchProject(proCode);

        PushReplayService.ReplayPreview preview = replayService.preview(proCode,
                new PushReplayService.ReplaySelection(true, proCode, null, PushTaskType.COMPANY,
                        companyCode, LocalDate.now(), LocalDate.now()));
        assertThat(preview.total()).isEqualTo(1);
        assertThat(preview.byType()).containsEntry(PushTaskType.COMPANY, 1);
        assertThat(preview.batchCount()).isEqualTo(1);

        PushReplayService.ReplayPreview outsideDate = replayService.preview(proCode,
                new PushReplayService.ReplaySelection(true, proCode, null, PushTaskType.COMPANY,
                        companyCode, LocalDate.now().minusDays(1), LocalDate.now().minusDays(1)));
        assertThat(outsideDate.total()).isZero();
    }

    @Test
    void filteredReplayCanGenerateMissingTasksFromCurrentMasterDataAndBatchPushThem() {
        String proCode = "P-REPLAY-REBUILD-001";
        String companyCode = "91110000123456789X";
        writeService.createProject(new MasterDataWriteRequest.Project(proCode, "无历史任务补推项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company(proCode, companyCode, "无历史任务补推企业",
                "LAOWU_CANJIAN", "Y", null, null, null, null, null, null, "N", null));

        PushReplayService.ReplayPreview preview = replayService.preview(proCode,
                new PushReplayService.ReplaySelection(true, proCode, null, null,
                        null, null, null, true));
        assertThat(preview.refreshSources()).isTrue();

        PushReplayService.ReplayJobView created = replayService.createAndStart(proCode,
                new PushReplayService.ReplaySelection(true, proCode, null, null,
                        null, null, null, true),
                "首次全量补推");
        assertThat(created.status()).isEqualTo(PushReplayJobStatus.CREATED);
        assertThat(created.warning()).contains("后台正在准备");

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(replayService.job(proCode, created.id()).status())
                        .isEqualTo(PushReplayJobStatus.SUCCESS));

        List<PushTask> sourceTasks = taskRepository.findByProCodeAndReplayJobIdIsNull(proCode);
        assertThat(sourceTasks).extracting(PushTask::getTaskType)
                .containsExactlyInAnyOrder(PushTaskType.PROJECT, PushTaskType.COMPANY);
        assertThat(sourceTasks).allSatisfy(task -> {
            assertThat(task.getPayloadEncrypted()).isNotBlank();
            assertThat(task.getStatus()).isEqualTo(PushTaskStatus.WAITING_CONFIRM);
        });
        assertThat(replayItemRepository.findByJobIdOrderByTaskTypeAscIdAsc(created.id())).hasSize(2);
        assertThat(replayService.job(proCode, created.id()).totalBatchCount()).isEqualTo(2);

        PushTaskService.TaskSummary summary = taskService.taskSummary(proCode);
        assertThat(summary.totalCount()).isEqualTo(2);
        assertThat(summary.groups()).filteredOn(group -> group.totalCount() > 0).hasSize(2);
    }
}
