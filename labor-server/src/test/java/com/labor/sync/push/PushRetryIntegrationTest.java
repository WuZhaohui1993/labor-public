package com.labor.sync.push;

import com.labor.sync.integration.LaborPlatformGateway;
import com.labor.sync.integration.LaborPushResult;
import com.labor.sync.masterdata.MasterDataWriteRequest;
import com.labor.sync.masterdata.MasterDataWriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@Transactional
class PushRetryIntegrationTest {
    @Autowired MasterDataWriteService writeService;
    @Autowired PushTaskService taskService;
    @Autowired PushTaskRepository taskRepository;
    @MockitoBean LaborPlatformGateway gateway;

    @Test
    void failedTaskGetsBackoffAndCanBeRecoveredManually() {
        var project = writeService.createProject(new MasterDataWriteRequest.Project("P-RETRY-001", "重试测试项目", null));
        taskService.consumeOutbox();
        PushTask task = taskRepository.findAll().stream()
                .filter(item -> item.getAggregateId().equals(String.valueOf(project.getId())))
                .findFirst().orElseThrow();

        when(gateway.push(any(), anyString(), any(), anyList()))
                .thenReturn(new LaborPushResult(false, "REMOTE_BUSY", "平台繁忙", 503, "{\"success\":false}"));
        taskService.startProject("P-RETRY-001");
        taskService.execute(task.getId());
        PushTask failed = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PushTaskStatus.FAILED);
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getNextRetryAt()).isNotNull();

        taskService.retry(task.getId(), "人工补推验证");
        when(gateway.push(any(), anyString(), any(), anyList()))
                .thenReturn(new LaborPushResult(true, "200", "操作成功", 200, "{\"success\":true}"));
        taskService.execute(task.getId());
        PushTask success = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(success.getStatus()).isEqualTo(PushTaskStatus.SUCCESS);
        assertThat(success.getLastErrorMessage()).isNull();
    }

    @Test
    void dueFailureIsRetriedByIndependentCompensationWorker() {
        var project = writeService.createProject(
                new MasterDataWriteRequest.Project("P-AUTO-RETRY-001", "自动补偿测试项目", null));
        taskService.consumeOutbox();
        taskService.startProject("P-AUTO-RETRY-001");
        PushTask task = taskRepository.findByProCode("P-AUTO-RETRY-001").stream()
                .filter(item -> item.getAggregateId().equals(String.valueOf(project.getId())))
                .findFirst().orElseThrow();

        when(gateway.push(any(), anyString(), any(), anyList()))
                .thenReturn(new LaborPushResult(false, "REMOTE_BUSY", "平台繁忙", 503, "{\"success\":false}"));
        taskService.execute(task.getId());
        task.setNextRetryAt(Instant.now().minusSeconds(1));
        taskRepository.saveAndFlush(task);

        when(gateway.push(any(), anyString(), any(), anyList()))
                .thenReturn(new LaborPushResult(true, "200", "操作成功", 200, "{\"success\":true}"));
        taskService.dispatchDueRetries();

        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(PushTaskStatus.SUCCESS);
    }

    @Test
    void sameTypeTasksUseOneBatchRequestAndSplitToLocateInvalidItems() {
        var project = writeService.createProject(
                new MasterDataWriteRequest.Project("P-BATCH-001", "批量推送测试项目", null));
        writeService.createCompany(new MasterDataWriteRequest.Company("P-BATCH-001", "91110000111111111X",
                "批量企业一", "LAOWU_CANJIAN", "Y", null, null, null, null, null, null, "N", null));
        writeService.createCompany(new MasterDataWriteRequest.Company("P-BATCH-001", "91110000222222222X",
                "批量企业二", "LAOWU_CANJIAN", "Y", null, null, null, null, null, null, "N", null));
        taskService.consumeOutbox();
        taskService.startProject("P-BATCH-001");

        List<Integer> requestSizes = new ArrayList<>();
        when(gateway.push(any(), anyString(), any(), anyList())).thenAnswer(invocation -> {
            List<Map<String, Object>> payload = invocation.getArgument(3);
            requestSizes.add(payload.size());
            if (payload.size() > 1) {
                return new LaborPushResult(false, "VALIDATION_FAILED", "批次中存在错误数据", 200,
                        "{\"success\":false}");
            }
            return new LaborPushResult(true, "200", "操作成功", 200, "{\"success\":true}");
        });

        PushTask projectTask = taskRepository.findAll().stream()
                .filter(task -> task.getTaskType() == PushTaskType.PROJECT
                        && task.getAggregateId().equals(String.valueOf(project.getId())))
                .findFirst().orElseThrow();
        taskService.execute(projectTask.getId());
        List<Long> companyTaskIds = taskRepository.findAll().stream()
                .filter(task -> task.getTaskType() == PushTaskType.COMPANY)
                .map(PushTask::getId).toList();

        PushTaskService.BatchExecutionResult result = taskService.executeBatch(companyTaskIds);

        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.httpCalls()).isEqualTo(3);
        assertThat(requestSizes).containsExactly(1, 2, 1, 1);
        assertThat(taskRepository.findAllById(companyTaskIds))
                .allMatch(task -> task.getStatus() == PushTaskStatus.SUCCESS);
    }
}
