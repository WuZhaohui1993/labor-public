package com.labor.sync.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.audit.AuditService;
import com.labor.sync.common.ApiResponse;
import com.labor.sync.common.BusinessException;
import com.labor.sync.common.CryptoService;
import com.labor.sync.common.PageResponse;
import com.labor.sync.security.WorkspaceContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/labor/admin/push")
@PreAuthorize("hasAuthority('push:view')")
public class PushController {
    private final PushTaskRepository taskRepository;
    private final IntegrationCallLogRepository callLogRepository;
    private final PushTaskService taskService;
    private final PushReplayService replayService;
    private final AuditService auditService;
    private final PushPayloadService payloadService;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    @GetMapping("/tasks")
    public ApiResponse<PageResponse<TaskView>> tasks(@RequestParam(required = false) String proCode,
                                                     @RequestParam(required = false) PushTaskType taskType,
                                                     @RequestParam(required = false) PushTaskStatus status,
                                                     @RequestParam(defaultValue = "") String search,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        Specification<PushTask> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("proCode"), WorkspaceContext.proCode()));
            predicates.add(cb.isNull(root.get("replayJobId")));
            if (taskType != null) predicates.add(cb.equal(root.get("taskType"), taskType));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (!search.isBlank()) predicates.add(cb.or(cb.like(root.get("businessKey"), like(search)),
                    cb.like(root.get("idempotencyKey"), like(search))));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(taskRepository.findAll(spec, pageable), this::taskView));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskDetailView> task(@PathVariable Long id) {
        return ApiResponse.ok(taskDetailView(find(id)));
    }

    @GetMapping("/tasks/summary")
    public ApiResponse<PushTaskService.TaskSummary> taskSummary() {
        return ApiResponse.ok(taskService.taskSummary(WorkspaceContext.proCode()));
    }

    @PostMapping("/tasks/{id}/retry")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<TaskView> retry(@PathVariable Long id, @RequestBody(required = false) ReasonRequest request) {
        find(id);
        String reason = request == null || request.reason() == null || request.reason().isBlank() ? "人工补推" : request.reason();
        PushTask task = taskService.retry(id, reason);
        auditService.record("RETRY", "PUSH_TASK", id, reason);
        return ApiResponse.ok(taskView(task));
    }

    @PostMapping("/tasks/{id}/execute")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<PushTaskService.BatchExecutionResult> execute(
            @PathVariable Long id, @RequestBody(required = false) ReasonRequest request) {
        find(id);
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "人工立即执行" : request.reason();
        taskService.retry(id, reason);
        PushTaskService.BatchExecutionResult result = taskService.execute(id);
        auditService.record("EXECUTE", "PUSH_TASK", id, reason);
        return ApiResponse.ok(result);
    }

    @PostMapping("/tasks/batch/execute")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<PushTaskService.BatchExecutionResult> executeBatch(
            @Valid @RequestBody BatchExecuteRequest request) {
        taskService.retryBatch(request.taskIds(), WorkspaceContext.proCode(), request.reason());
        PushTaskService.BatchExecutionResult result = taskService.executeBatch(request.taskIds());
        auditService.record("BATCH_EXECUTE", "PUSH_TASK_BATCH", "count=" + result.requested(), request.reason());
        return ApiResponse.ok(result);
    }

    @PostMapping("/tasks/{id}/pause")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<TaskView> pause(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        find(id);
        PushTask task = taskService.pause(id, request.reason());
        auditService.record("PAUSE", "PUSH_TASK", id, request.reason());
        return ApiResponse.ok(taskView(task));
    }

    @PostMapping("/tasks/{id}/ignore")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<TaskView> ignore(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        find(id);
        PushTask task = taskService.ignore(id, request.reason());
        auditService.record("IGNORE", "PUSH_TASK", id, request.reason());
        return ApiResponse.ok(taskView(task));
    }

    @PostMapping("/rebuild")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<PushTaskService.RebuildResult> rebuild(@RequestParam String proCode) {
        requireWorkspace(proCode);
        PushTaskService.RebuildResult result = taskService.rebuildProject(WorkspaceContext.proCode());
        auditService.record("REBUILD", "PUSH_TASK", WorkspaceContext.proCode(), "新增任务 " + result.createdTasks());
        return ApiResponse.ok(result);
    }

    @PostMapping("/start")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<PushTaskService.StartResult> start(@RequestParam String proCode) {
        requireWorkspace(proCode);
        PushTaskService.StartResult result = taskService.startProject(WorkspaceContext.proCode());
        auditService.record("START_PROJECT_SYNC", "PROJECT", WorkspaceContext.proCode(),
                "激活任务 " + result.activatedTasks());
        return ApiResponse.ok(result);
    }

    @PostMapping("/execute-project")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<PushTaskService.ProjectExecutionResult> executeProject(@RequestParam String proCode) {
        requireWorkspace(proCode);
        taskService.startProject(proCode);
        taskService.prepareProjectImmediate(proCode);
        taskService.dispatchProject(proCode);
        PushTaskService.ProjectExecutionResult result = taskService.projectExecutionResult(proCode);
        auditService.record("EXECUTE_PROJECT", "PROJECT", proCode,
                "成功 " + result.succeeded() + "，失败 " + result.failed() + "，等待 " + result.pending());
        return ApiResponse.ok(result);
    }

    @PostMapping("/replays/preview")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<PushReplayService.ReplayPreview> replayPreview(@Valid @RequestBody ReplaySelectionRequest request) {
        return ApiResponse.ok(replayService.preview(WorkspaceContext.proCode(), request.selection()));
    }

    @PostMapping("/replays")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<PushReplayService.ReplayJobView> createReplay(@Valid @RequestBody ReplayRequest request) {
        PushReplayService.ReplayJobView job = replayService.createAndStart(
                WorkspaceContext.proCode(), request.selection(), request.reason());
        auditService.record("CREATE_REPLAY", "PUSH_REPLAY_JOB", job.id(), request.reason());
        return ApiResponse.ok(job);
    }

    @GetMapping("/replays")
    public ApiResponse<List<PushReplayService.ReplayJobView>> replayJobs() {
        return ApiResponse.ok(replayService.jobs(WorkspaceContext.proCode()));
    }

    @GetMapping("/replays/{id}")
    public ApiResponse<PushReplayService.ReplayJobView> replayJob(@PathVariable Long id) {
        return ApiResponse.ok(replayService.job(WorkspaceContext.proCode(), id));
    }

    @GetMapping("/replays/{id}/items")
    public ApiResponse<List<PushReplayService.ReplayItemView>> replayItems(@PathVariable Long id) {
        return ApiResponse.ok(replayService.items(WorkspaceContext.proCode(), id));
    }

    @PostMapping("/replays/{id}/retry-failed")
    @PreAuthorize("hasAuthority('push:operate')")
    public ApiResponse<PushReplayService.ReplayJobView> retryReplayFailed(
            @PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        PushReplayService.ReplayJobView job = replayService.retryFailed(
                WorkspaceContext.proCode(), id, request.reason());
        auditService.record("RETRY_REPLAY", "PUSH_REPLAY_JOB", id, request.reason());
        return ApiResponse.ok(job);
    }

    @GetMapping("/call-logs")
    public ApiResponse<PageResponse<CallLogView>> logs(@RequestParam(required = false) String integrationType,
                                                       @RequestParam(required = false) String operationType,
                                                       @RequestParam(required = false) Boolean success,
                                                       @RequestParam(required = false) Long taskId,
                                                       @RequestParam(required = false) Long replayJobId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Specification<IntegrationCallLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("project").get("id"), WorkspaceContext.projectId()));
            if (integrationType != null && !integrationType.isBlank()) predicates.add(cb.equal(root.get("integrationType"), integrationType));
            if (operationType != null && !operationType.isBlank()) predicates.add(cb.equal(root.get("operationType"), operationType));
            if (success != null) predicates.add(cb.equal(root.get("success"), success));
            if (taskId != null) predicates.add(cb.equal(root.get("taskId"), taskId));
            if (replayJobId != null) predicates.add(cb.equal(root.get("replayJobId"), replayJobId));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(callLogRepository.findAll(spec, pageable), CallLogView::from));
    }

    private PushTask find(Long id) {
        PushTask task = taskRepository.findById(id)
                .orElseThrow(() -> new BusinessException("PUSH_TASK_NOT_FOUND", "推送任务不存在", HttpStatus.NOT_FOUND));
        if (!WorkspaceContext.proCode().equals(task.getProCode()) || task.getReplayJobId() != null) {
            throw new BusinessException("PUSH_TASK_NOT_FOUND", "推送任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private String like(String value) {
        return "%" + value.trim() + "%";
    }

    private void requireWorkspace(String proCode) {
        if (!WorkspaceContext.proCode().equals(proCode)) {
            throw new BusinessException("WORKSPACE_DATA_MISMATCH", "操作项目与当前工作区不一致");
        }
    }

    private TaskView taskView(PushTask task) {
        return TaskView.from(task, taskService.isDependencyReady(task));
    }

    private TaskDetailView taskDetailView(PushTask task) {
        String payloadJson = null;
        boolean snapshot = task.getPayloadEncrypted() != null && !task.getPayloadEncrypted().isBlank();
        String payloadMessage = null;
        try {
            payloadJson = snapshot
                    ? cryptoService.decrypt(task.getPayloadEncrypted())
                    : objectMapper.writeValueAsString(payloadService.build(task).payload());
            if (!snapshot) payloadMessage = "历史任务没有保存报文快照，以下内容根据当前业务数据生成，可能与原任务创建时不同";
        } catch (Exception exception) {
            payloadMessage = "推送数据暂时无法解析：" + exception.getMessage();
        }
        return TaskDetailView.from(task, taskService.isDependencyReady(task), payloadJson, snapshot, payloadMessage);
    }

    public record ReasonRequest(@NotBlank String reason) {
    }

    public record BatchExecuteRequest(@NotEmpty @Size(max = 200) List<@NotNull Long> taskIds,
                                      @NotBlank @Size(max = 500) String reason) {
    }

    public record TaskView(Long id, PushTaskType taskType, String aggregateType, String aggregateId,
                           String proCode, String businessKey, int dataVersionNo, String idempotencyKey,
                           String dependencyKey, PushTaskStatus status, int retryCount, int maxRetries,
                           Instant nextRetryAt, String lastErrorCode, String lastErrorMessage,
                           String remoteCode, String remoteMessage, String manualReason,
                           boolean dependencyReady, Instant startedAt, Instant completedAt,
                           Instant createdAt, Instant updatedAt) {
        static TaskView from(PushTask task, boolean dependencyReady) {
            return new TaskView(task.getId(), task.getTaskType(), task.getAggregateType(), task.getAggregateId(),
                    task.getProCode(), task.getBusinessKey(), task.getDataVersionNo(), task.getIdempotencyKey(),
                    task.getDependencyKey(), task.getStatus(), task.getRetryCount(), task.getMaxRetries(),
                    task.getNextRetryAt(), task.getLastErrorCode(), task.getLastErrorMessage(), task.getRemoteCode(),
                    task.getRemoteMessage(), task.getManualReason(), dependencyReady,
                    task.getStartedAt(), task.getCompletedAt(),
                    task.getCreatedAt(), task.getUpdatedAt());
        }
    }

    public record TaskDetailView(Long id, PushTaskType taskType, String aggregateType, String aggregateId,
                                 String proCode, String businessKey, int dataVersionNo, String idempotencyKey,
                                 String dependencyKey, PushTaskStatus status, int retryCount, int maxRetries,
                                 Instant nextRetryAt, String lastErrorCode, String lastErrorMessage,
                                 String remoteCode, String remoteMessage, String manualReason,
                                 boolean dependencyReady, Instant startedAt, Instant completedAt,
                                 Instant createdAt, Instant updatedAt, String payloadJson,
                                 boolean payloadSnapshot, String payloadMessage) {
        static TaskDetailView from(PushTask task, boolean dependencyReady, String payloadJson,
                                   boolean payloadSnapshot, String payloadMessage) {
            return new TaskDetailView(task.getId(), task.getTaskType(), task.getAggregateType(), task.getAggregateId(),
                    task.getProCode(), task.getBusinessKey(), task.getDataVersionNo(), task.getIdempotencyKey(),
                    task.getDependencyKey(), task.getStatus(), task.getRetryCount(), task.getMaxRetries(),
                    task.getNextRetryAt(), task.getLastErrorCode(), task.getLastErrorMessage(), task.getRemoteCode(),
                    task.getRemoteMessage(), task.getManualReason(), dependencyReady,
                    task.getStartedAt(), task.getCompletedAt(), task.getCreatedAt(), task.getUpdatedAt(),
                    payloadJson, payloadSnapshot, payloadMessage);
        }
    }

    public record CallLogView(Long id, String integrationType, String operationType, Long taskId,
                              Long replayJobId, int batchSize,
                              String requestPath, String requestSummaryJson, Integer httpStatus,
                              String responseSummaryJson, boolean success, long durationMs,
                              String errorCode, String errorMessage, String traceId, Instant createdAt) {
        static CallLogView from(IntegrationCallLog log) {
            return new CallLogView(log.getId(), log.getIntegrationType(), log.getOperationType(), log.getTaskId(),
                    log.getReplayJobId(), log.getBatchSize(),
                    log.getRequestPath(), log.getRequestSummaryJson(), log.getHttpStatus(), log.getResponseSummaryJson(),
                    log.isSuccess(), log.getDurationMs(), log.getErrorCode(), log.getErrorMessage(),
                    log.getTraceId(), log.getCreatedAt());
        }
    }

    public record ReplaySelectionRequest(boolean projectWide, String projectCode, List<Long> taskIds,
                                         PushTaskType taskType, String companyCode,
                                         java.time.LocalDate startDate, java.time.LocalDate endDate,
                                         boolean refreshSources) {
        PushReplayService.ReplaySelection selection() {
            return new PushReplayService.ReplaySelection(projectWide, projectCode, taskIds, taskType,
                    companyCode, startDate, endDate, refreshSources);
        }
    }

    public record ReplayRequest(boolean projectWide, List<Long> taskIds, PushTaskType taskType,
                                String projectCode, String companyCode,
                                java.time.LocalDate startDate, java.time.LocalDate endDate,
                                boolean refreshSources,
                                @NotBlank @Size(max = 500) String reason) {
        PushReplayService.ReplaySelection selection() {
            return new PushReplayService.ReplaySelection(projectWide, projectCode, taskIds, taskType,
                    companyCode, startDate, endDate, refreshSources);
        }
    }
}
