package com.labor.sync.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labor.sync.common.BusinessException;
import com.labor.sync.integration.IntegrationProperties;
import com.labor.sync.masterdata.CompanyRepository;
import com.labor.sync.masterdata.LaborCompany;
import com.labor.sync.masterdata.LaborProject;
import com.labor.sync.masterdata.MasterDataStatus;
import com.labor.sync.masterdata.ProjectRepository;
import com.labor.sync.matching.PersonMatchingService;
import com.labor.sync.security.SecurityUtils;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushReplayService {
    private static final int MAX_REPLAY_ITEMS = 10_000;
    private static final Set<PushReplayJobStatus> ACTIVE_STATUSES =
            Set.of(PushReplayJobStatus.CREATED, PushReplayJobStatus.RUNNING);
    private static final DateTimeFormatter JOB_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final PushTaskRepository taskRepository;
    private final PushReplayJobRepository jobRepository;
    private final PushReplayItemRepository itemRepository;
    private final ProjectRepository projectRepository;
    private final CompanyRepository companyRepository;
    private final PersonMatchingService matchingService;
    private final PushTaskService taskService;
    private final IntegrationProperties properties;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, replayThreadFactory());

    @Transactional(readOnly = true)
    public ReplayPreview preview(String proCode, ReplaySelection selection) {
        LaborProject project = projectRepository.findByProCode(proCode)
                .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
        ReplayCriteria criteria = criteria(proCode, selection, project);
        try {
            return summarize(selectSources(proCode, selection,
                    selection.projectWide() && selection.refreshSources()), criteria,
                    selection.projectWide() && selection.refreshSources(), null);
        } catch (BusinessException exception) {
            if (!"REPLAY_TASKS_EMPTY".equals(exception.getCode())) throw exception;
            return summarize(List.of(), criteria,
                    selection.projectWide() && selection.refreshSources(), null);
        }
    }

    @Transactional
    public ReplayJobView createAndStart(String proCode, ReplaySelection selection, String reason) {
        String normalizedReason = requiredReason(reason);
        LaborProject project = projectRepository.findByProCodeForUpdate(proCode)
                .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
        ReplayCriteria criteria = criteria(proCode, selection, project);
        jobRepository.findFirstByProCodeAndStatusIn(proCode, ACTIVE_STATUSES).ifPresent(active -> {
            throw new BusinessException("REPLAY_JOB_RUNNING",
                    "当前项目已有补推作业正在执行：" + active.getJobNo());
        });

        PushReplayJob job = new PushReplayJob();
        job.setProject(project);
        job.setJobNo(jobNo());
        job.setProCode(proCode);
        job.setScopeType(selection.projectWide()
                ? selection.refreshSources() ? "REFRESHED" : "FILTERED"
                : "SELECTED");
        job.setScopeDescription(scopeDescription(selection, criteria,
                selection.projectWide() ? 0 : selection.taskIds() == null ? 0 : selection.taskIds().size()));
        job.setSelectionJson(writeSelection(selection));
        job.setTaskType(selection.taskType());
        job.setCompanyCode(criteria.companyCode());
        job.setCompanyName(criteria.companyName());
        job.setStartDate(selection.startDate());
        job.setEndDate(selection.endDate());
        job.setTotalBatchCount(0);
        job.setReason(normalizedReason);
        job.setRequestedBy(SecurityUtils.currentUsername());
        job.setStatus(PushReplayJobStatus.CREATED);
        job.setTotalCount(0);
        job.setPendingCount(0);
        job = jobRepository.save(job);
        Long jobId = job.getId();
        submitAfterCommit(jobId);
        return view(job, pendingPreview(criteria, selection));
    }

    @Transactional
    public ReplayJobView retryFailed(String proCode, Long jobId, String reason) {
        projectRepository.findByProCodeForUpdate(proCode)
                .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
        PushReplayJob job = findJob(proCode, jobId);
        if (job.getStatus() == PushReplayJobStatus.RUNNING) {
            throw new BusinessException("REPLAY_JOB_RUNNING", "补推作业正在执行");
        }
        jobRepository.findFirstByProCodeAndStatusIn(proCode, ACTIVE_STATUSES).ifPresent(active -> {
            if (!active.getId().equals(jobId)) {
                throw new BusinessException("REPLAY_JOB_RUNNING",
                        "当前项目已有补推作业正在执行：" + active.getJobNo());
            }
        });
        List<PushReplayItem> failed = itemRepository.findByJobIdAndStatusIn(jobId,
                Set.of(PushReplayItemStatus.FAILED, PushReplayItemStatus.PAUSED));
        if (failed.isEmpty()) throw new BusinessException("REPLAY_NO_FAILED_ITEMS", "补推作业没有失败项");
        String normalizedReason = requiredReason(reason);
        for (PushReplayItem item : failed) {
            PushTask replayTask = taskRepository.findById(item.getReplayTaskId())
                    .orElseThrow(() -> new BusinessException("PUSH_TASK_NOT_FOUND", "补推任务不存在"));
            replayTask.setStatus(PushTaskStatus.PENDING);
            replayTask.setRetryCount(0);
            replayTask.setNextRetryAt(null);
            replayTask.setStartedAt(null);
            replayTask.setCompletedAt(null);
            replayTask.setLastErrorCode(null);
            replayTask.setLastErrorMessage(null);
            replayTask.setManualReason(abbreviate("补推失败项重试：" + normalizedReason, 500));
            taskRepository.save(replayTask);
            item.setStatus(PushReplayItemStatus.PENDING);
            item.setAttemptCount(0);
            item.setLastErrorCode(null);
            item.setLastErrorMessage(null);
            item.setCompletedAt(null);
            itemRepository.save(item);
        }
        job.setStatus(PushReplayJobStatus.CREATED);
        job.setPendingCount(failed.size());
        job.setFailedCount(0);
        job.setCompletedAt(null);
        job.setLastError(null);
        jobRepository.save(job);
        submitAfterCommit(jobId);
        return view(job, summarizeItems(itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(jobId), job));
    }

    @Transactional(readOnly = true)
    public List<ReplayJobView> jobs(String proCode) {
        return jobRepository.findByProCodeOrderByCreatedAtDesc(proCode).stream()
                .limit(100)
                .map(job -> view(job, summarizeItems(itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(job.getId()), job)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReplayJobView job(String proCode, Long jobId) {
        PushReplayJob job = findJob(proCode, jobId);
        return view(job, summarizeItems(itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(jobId), job));
    }

    @Transactional(readOnly = true)
    public List<ReplayItemView> items(String proCode, Long jobId) {
        findJob(proCode, jobId);
        return itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(jobId).stream()
                .map(ReplayItemView::from)
                .toList();
    }

    private void executeJob(Long jobId) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                PushReplayJob job = jobRepository.findById(jobId).orElseThrow();
                job.setStatus(PushReplayJobStatus.RUNNING);
                if (job.getStartedAt() == null) job.setStartedAt(Instant.now());
                job.setLastError(null);
                jobRepository.save(job);
            });
            List<PushReplayItem> items = itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(jobId).stream()
                    .filter(item -> item.getStatus() == PushReplayItemStatus.PENDING)
                    .sorted(Comparator.comparingInt(item -> item.getTaskType().ordinal()))
                    .toList();
            List<Long> taskIds = items.stream().map(PushReplayItem::getReplayTaskId).toList();
            int executionChunkSize = Math.max(1, properties.getWorker().getBatchSize());
            for (int start = 0; start < taskIds.size(); start += executionChunkSize) {
                List<Long> chunk = taskIds.subList(start, Math.min(taskIds.size(), start + executionChunkSize));
                taskService.executeBatch(chunk);
                refreshJob(jobId, false, null);
            }
            refreshJob(jobId, true, null);
        } catch (Exception exception) {
            log.error("push replay job failed jobId={} reason={}", jobId, exception.getMessage(), exception);
            refreshJob(jobId, true, exception.getMessage());
        }
    }

    private void processJob(Long jobId) {
        try {
            boolean hasItems = new TransactionTemplate(transactionManager).execute(status ->
                    itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(jobId).stream().findAny().isPresent());
            if (!hasItems) prepareJob(jobId);
            executeJob(jobId);
        } catch (Exception exception) {
            log.error("push replay job preparation failed jobId={} reason={}", jobId, exception.getMessage(), exception);
            markPreparationFailed(jobId, exception.getMessage());
        }
    }

    private void prepareJob(Long jobId) {
        ReplaySelection selection = new TransactionTemplate(transactionManager).execute(status -> {
            PushReplayJob job = jobRepository.findById(jobId).orElseThrow();
            if (itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(jobId).stream().findAny().isPresent()) {
                return null;
            }
            return readSelection(job);
        });
        if (selection == null) return;

        String proCode = selection.projectCode();
        String refreshMessage = null;
        if (selection.projectWide() && selection.refreshSources()) {
            PersonMatchingService.BatchMatchResult matching = null;
            if (selection.taskType() == null || selection.taskType() == PushTaskType.ATTENDANCE) {
                matching = matchingService.rematchUnmatched(proCode, MAX_REPLAY_ITEMS);
            }
            PushTaskService.RebuildResult rebuilt = taskService.rebuildReplaySources(proCode, selection.taskType());
            refreshMessage = (matching == null ? "" : "已自动匹配 " + matching.matched() + " 条考勤，")
                    + "补齐 " + rebuilt.createdTasks() + " 条推送任务";
        }
        String finalRefreshMessage = refreshMessage;
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            PushReplayJob job = jobRepository.findById(jobId).orElseThrow();
            if (itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(jobId).stream().findAny().isPresent()) return;
            LaborProject project = projectRepository.findByProCode(job.getProCode())
                    .orElseThrow(() -> new BusinessException("PROJECT_NOT_FOUND", "项目编码不存在"));
            ReplayCriteria criteria = criteria(job.getProCode(), selection, project);
            List<PushTask> sources = selectSources(job.getProCode(), selection,
                    selection.projectWide() && selection.refreshSources());
            ReplayPreview preview = summarize(sources, criteria,
                    selection.projectWide() && selection.refreshSources(), finalRefreshMessage);
            job.setScopeDescription(scopeDescription(selection, criteria, sources.size()));
            job.setTotalBatchCount(preview.batchCount());
            job.setTotalCount(preview.total());
            job.setPendingCount(preview.total());
            jobRepository.save(job);
            for (PushTask source : sources) {
                if (source.getPayloadEncrypted() == null || source.getPayloadEncrypted().isBlank()) {
                    throw new BusinessException("REPLAY_PAYLOAD_MISSING",
                            "任务 " + source.getId() + " 没有可重放的原始报文快照");
                }
                PushTask replayTask = cloneTask(job, source, job.getReason());
                PushReplayItem item = new PushReplayItem();
                item.setJob(job);
                item.setSourceTaskId(source.getId());
                item.setReplayTaskId(replayTask.getId());
                item.setTaskType(source.getTaskType());
                item.setStatus(PushReplayItemStatus.PENDING);
                item.setMaxRetries(replayTask.getMaxRetries());
                itemRepository.save(item);
            }
        });
    }

    private void markPreparationFailed(Long jobId, String error) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            PushReplayJob job = jobRepository.findById(jobId).orElse(null);
            if (job == null || job.getStatus() != PushReplayJobStatus.CREATED) return;
            job.setStatus(PushReplayJobStatus.FAILED);
            job.setPendingCount(0);
            job.setCompletedAt(Instant.now());
            job.setLastError(abbreviate(error, 1000));
            jobRepository.save(job);
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedJobs() {
        for (PushReplayJob job : jobRepository.findByStatusIn(ACTIVE_STATUSES)) {
            if (job.getStatus() == PushReplayJobStatus.CREATED) {
                executor.submit(() -> processJob(job.getId()));
                continue;
            }
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                PushReplayJob current = jobRepository.findById(job.getId()).orElseThrow();
                List<PushReplayItem> items = itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(current.getId());
                for (PushReplayItem item : items) {
                    if (item.getStatus() == PushReplayItemStatus.SUCCESS) continue;
                    taskRepository.findById(item.getReplayTaskId()).ifPresent(task -> {
                        if (task.getStatus() != PushTaskStatus.SUCCESS) {
                            task.setStatus(PushTaskStatus.FAILED);
                            task.setNextRetryAt(null);
                            task.setLastErrorCode("REPLAY_WORKER_INTERRUPTED");
                            task.setLastErrorMessage("补推进程因服务重启中断，请人工重试失败项");
                            taskRepository.save(task);
                        }
                    });
                    item.setStatus(PushReplayItemStatus.FAILED);
                    item.setLastErrorCode("REPLAY_WORKER_INTERRUPTED");
                    item.setLastErrorMessage("补推进程因服务重启中断，请人工重试失败项");
                    itemRepository.save(item);
                }
                int success = (int) items.stream()
                        .filter(item -> item.getStatus() == PushReplayItemStatus.SUCCESS).count();
                current.setSuccessCount(success);
                current.setFailedCount(items.size() - success);
                current.setPendingCount(0);
                current.setStatus(success > 0 ? PushReplayJobStatus.PARTIAL_SUCCESS : PushReplayJobStatus.FAILED);
                current.setCompletedAt(Instant.now());
                current.setLastError("补推进程因服务重启中断，请人工重试失败项");
                jobRepository.save(current);
            });
        }
    }

    private void refreshJob(Long jobId, boolean complete, String error) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            PushReplayJob job = jobRepository.findById(jobId).orElseThrow();
            List<PushReplayItem> items = itemRepository.findByJobIdOrderByTaskTypeAscIdAsc(jobId);
            Map<Long, PushTask> tasks = new LinkedHashMap<>();
            taskRepository.findAllById(items.stream().map(PushReplayItem::getReplayTaskId).toList())
                    .forEach(task -> tasks.put(task.getId(), task));
            for (PushReplayItem item : items) {
                PushTask task = tasks.get(item.getReplayTaskId());
                if (task == null) continue;
                item.setStatus(itemStatus(task.getStatus()));
                item.setAttemptCount(task.getRetryCount());
                item.setLastErrorCode(task.getLastErrorCode());
                item.setLastErrorMessage(task.getLastErrorMessage());
                item.setCompletedAt(task.getCompletedAt());
                itemRepository.save(item);
            }
            int success = (int) items.stream().filter(item -> item.getStatus() == PushReplayItemStatus.SUCCESS).count();
            int failed = (int) items.stream().filter(item -> Set.of(
                    PushReplayItemStatus.FAILED, PushReplayItemStatus.PAUSED).contains(item.getStatus())).count();
            int pending = items.size() - success - failed;
            job.setSuccessCount(success);
            job.setFailedCount(failed);
            job.setPendingCount(pending);
            job.setLastError(abbreviate(error, 1000));
            if (complete) {
                job.setCompletedAt(Instant.now());
                job.setStatus(failed == 0 && pending == 0 ? PushReplayJobStatus.SUCCESS
                        : success > 0 ? PushReplayJobStatus.PARTIAL_SUCCESS : PushReplayJobStatus.FAILED);
            }
            jobRepository.save(job);
        });
    }

    private List<PushTask> selectSources(String proCode, ReplaySelection selection, boolean currentData) {
        validateSelection(proCode, selection);
        List<PushTask> result;
        if (selection.projectWide()) {
            Map<String, PushTask> latest = new LinkedHashMap<>();
            List<PushTask> candidates = currentData
                    ? taskRepository.findByProCodeAndReplayJobIdIsNull(proCode)
                    : taskRepository.findByProCodeAndStatusAndReplayJobIdIsNull(proCode, PushTaskStatus.SUCCESS);
            candidates.stream()
                    .filter(task -> selection.taskType() == null || task.getTaskType() == selection.taskType())
                    .forEach(task -> latest.merge(task.getTaskType() + ":" + task.getAggregateId(), task,
                            (left, right) -> right.getDataVersionNo() > left.getDataVersionNo()
                                    || (right.getDataVersionNo() == left.getDataVersionNo()
                                    && right.getCreatedAt().isAfter(left.getCreatedAt())) ? right : left));
            result = new ArrayList<>(latest.values());
        } else {
            List<Long> ids = selection.taskIds() == null ? List.of()
                    : selection.taskIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
            if (ids.isEmpty()) throw new BusinessException("REPLAY_TASKS_REQUIRED", "请选择需要重新推送的成功任务");
            result = taskRepository.findAllById(ids);
            if (result.size() != ids.size()) throw new BusinessException("PUSH_TASK_NOT_FOUND", "部分推送任务不存在");
        }
        if (result.isEmpty()) {
            throw new BusinessException("REPLAY_TASKS_EMPTY", currentData
                    ? "当前范围没有可生成的推送任务" : "当前范围没有可重新推送的成功任务");
        }
        if (result.stream().anyMatch(task -> !proCode.equals(task.getProCode()) || task.getReplayJobId() != null)) {
            throw new BusinessException("WORKSPACE_DATA_MISMATCH", "只能补推当前项目的原始任务");
        }
        if (!currentData && result.stream().anyMatch(task -> task.getStatus() != PushTaskStatus.SUCCESS)) {
            throw new BusinessException("REPLAY_SOURCE_NOT_SUCCESS", "补推只适用于已成功任务，失败任务请使用恢复或立即执行");
        }
        if (currentData) {
            result = result.stream().filter(task -> Set.of(PushTaskStatus.WAITING_CONFIRM,
                    PushTaskStatus.PENDING, PushTaskStatus.SUCCESS, PushTaskStatus.FAILED)
                    .contains(task.getStatus())).toList();
        }
        Set<String> enabledCompanyCodes = companyRepository
                .findByProCodeAndStatusNotOrderByCompanyNameAsc(proCode, MasterDataStatus.DISABLED)
                .stream().filter(LaborCompany::isPersonPushEnabled).map(LaborCompany::getCollCropCode)
                .collect(java.util.stream.Collectors.toSet());
        boolean containsDisabledPersonData = result.stream()
                .filter(task -> Set.of(PushTaskType.PERSON, PushTaskType.ATTENDANCE).contains(task.getTaskType()))
                .anyMatch(task -> !enabledCompanyCodes.contains(task.getCompanyCode()));
        if (!selection.projectWide() && containsDisabledPersonData) {
            throw new BusinessException("COMPANY_PERSON_PUSH_DISABLED", "选中任务所属参建企业已关闭人员及考勤推送");
        }
        result = result.stream()
                .filter(task -> !Set.of(PushTaskType.PERSON, PushTaskType.ATTENDANCE).contains(task.getTaskType())
                        || enabledCompanyCodes.contains(task.getCompanyCode()))
                .toList();
        String companyCode = normalized(selection.companyCode());
        result = result.stream()
                .filter(task -> companyCode == null || companyCode.equals(task.getCompanyCode()))
                .filter(task -> inDateRange(sourceDate(task), selection.startDate(), selection.endDate()))
                .toList();
        if (result.isEmpty()) {
            throw new BusinessException("REPLAY_TASKS_EMPTY", currentData
                    ? "当前筛选范围没有可生成的推送任务" : "当前筛选范围没有可重新推送的成功任务");
        }
        if (result.size() > MAX_REPLAY_ITEMS) {
            throw new BusinessException("REPLAY_TOO_LARGE", "单次补推最多支持 " + MAX_REPLAY_ITEMS + " 条任务");
        }
        return result.stream().sorted(Comparator.comparingInt(PushTask::getPriority)
                .thenComparing(PushTask::getCreatedAt)).toList();
    }

    private PushTask cloneTask(PushReplayJob job, PushTask source, String reason) {
        PushTask replay = new PushTask();
        replay.setTaskType(source.getTaskType());
        replay.setAggregateType(source.getAggregateType());
        replay.setAggregateId(source.getAggregateId());
        replay.setProCode(source.getProCode());
        replay.setBusinessKey(source.getBusinessKey());
        replay.setCompanyCode(source.getCompanyCode());
        replay.setBusinessDate(source.getBusinessDate());
        replay.setBusinessAt(source.getBusinessAt());
        replay.setDataVersionNo(source.getDataVersionNo());
        replay.setIdempotencyKey("REPLAY:" + job.getJobNo() + ":" + source.getId());
        replay.setDependencyKey(null);
        replay.setPayloadEncrypted(source.getPayloadEncrypted());
        replay.setStatus(PushTaskStatus.PENDING);
        replay.setPriority(source.getPriority());
        replay.setMaxRetries(properties.getWorker().getMaxRetries());
        replay.setManualReason(abbreviate("补推作业 " + job.getJobNo() + "：" + reason, 500));
        replay.setReplayJobId(job.getId());
        replay.setReplaySourceTaskId(source.getId());
        return taskRepository.save(replay);
    }

    private ReplayPreview summarize(List<PushTask> sources, ReplayCriteria criteria,
                                    boolean refreshSources, String refreshMessage) {
        Map<PushTaskType, Integer> counts = new EnumMap<>(PushTaskType.class);
        sources.forEach(task -> counts.merge(task.getTaskType(), 1, Integer::sum));
        int batchSize = Math.max(1, properties.getWorker().getBatchSize());
        int batches = counts.values().stream().mapToInt(count -> (count + batchSize - 1) / batchSize).sum();
        int attendance = counts.getOrDefault(PushTaskType.ATTENDANCE, 0);
        List<String> warnings = new ArrayList<>();
        if (refreshSources) warnings.add("创建作业前会先自动匹配未匹配考勤，并按当前主数据补齐最新推送任务");
        if (attendance > 0) warnings.add("考勤补推可能产生接收端重复记录，请确认接收端去重规则");
        if (refreshMessage != null) warnings.add(refreshMessage);
        return new ReplayPreview(criteria.projectCode(), criteria.projectName(), criteria.taskType(),
                criteria.companyCode(), criteria.companyName(), criteria.startDate(), criteria.endDate(),
                sources.size(), counts, batches, attendance, refreshSources,
                warnings.isEmpty() ? null : String.join("；", warnings));
    }

    private ReplayPreview summarizeItems(Collection<PushReplayItem> items, PushReplayJob job) {
        Map<PushTaskType, Integer> counts = new EnumMap<>(PushTaskType.class);
        items.forEach(item -> counts.merge(item.getTaskType(), 1, Integer::sum));
        int batchSize = Math.max(1, properties.getWorker().getBatchSize());
        int batches = counts.values().stream().mapToInt(count -> (count + batchSize - 1) / batchSize).sum();
        int attendance = counts.getOrDefault(PushTaskType.ATTENDANCE, 0);
        boolean refreshSources = "REFRESHED".equals(job.getScopeType());
        String warning = items.isEmpty() && job.getStatus() == PushReplayJobStatus.CREATED
                ? "补推作业已创建，后台正在准备当前数据和推送任务"
                : refreshSources || attendance > 0
                ? (refreshSources ? "该作业包含按当前主数据重新生成的任务" : "考勤补推可能产生接收端重复记录，请确认接收端去重规则")
                : null;
        return new ReplayPreview(job.getProCode(), job.getProject().getProjectName(), job.getTaskType(),
                job.getCompanyCode(), job.getCompanyName(), job.getStartDate(), job.getEndDate(),
                items.size(), counts, job.getTotalBatchCount() > 0 ? job.getTotalBatchCount() : batches,
                attendance, refreshSources, warning);
    }

    private void submitAfterCommit(Long jobId) {
        Runnable submit = () -> executor.submit(() -> processJob(jobId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit.run();
                }
            });
        } else {
            submit.run();
        }
    }

    private PushReplayJob findJob(String proCode, Long jobId) {
        PushReplayJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BusinessException("REPLAY_JOB_NOT_FOUND", "补推作业不存在", HttpStatus.NOT_FOUND));
        if (!proCode.equals(job.getProCode())) {
            throw new BusinessException("REPLAY_JOB_NOT_FOUND", "补推作业不存在", HttpStatus.NOT_FOUND);
        }
        return job;
    }

    private ReplayJobView view(PushReplayJob job, ReplayPreview preview) {
        int completed = job.getSuccessCount() + job.getFailedCount();
        int progress = job.getTotalCount() == 0 ? 0
                : Math.min(100, (int) Math.round(completed * 100.0 / job.getTotalCount()));
        return new ReplayJobView(job.getId(), job.getJobNo(), job.getScopeType(), job.getScopeDescription(),
                job.getProCode(), job.getProject().getProjectName(), job.getTaskType(), job.getCompanyCode(),
                job.getCompanyName(), job.getStartDate(), job.getEndDate(), job.getReason(), job.getRequestedBy(),
                job.getStatus(), job.getTotalCount(), job.getSuccessCount(), job.getFailedCount(), job.getPendingCount(),
                completed, progress, job.getTotalBatchCount(), preview == null ? null : preview.byType(),
                preview == null ? null : preview.warning(), job.getStartedAt(), job.getCompletedAt(),
                job.getLastError(), job.getCreatedAt());
    }

    private ReplayPreview pendingPreview(ReplayCriteria criteria, ReplaySelection selection) {
        boolean refresh = selection.projectWide() && selection.refreshSources();
        return new ReplayPreview(criteria.projectCode(), criteria.projectName(), criteria.taskType(),
                criteria.companyCode(), criteria.companyName(), criteria.startDate(), criteria.endDate(),
                0, Map.of(), 0, 0, refresh,
                "补推作业已创建，后台正在准备当前数据和推送任务");
    }

    private String writeSelection(ReplaySelection selection) {
        try {
            return objectMapper.writeValueAsString(selection);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("REPLAY_SELECTION_INVALID", "补推筛选条件无法保存");
        }
    }

    private ReplaySelection readSelection(PushReplayJob job) {
        if (job.getSelectionJson() == null || job.getSelectionJson().isBlank()) {
            if (!"SELECTED".equals(job.getScopeType())) {
                return new ReplaySelection(true, job.getProCode(), null, job.getTaskType(),
                        job.getCompanyCode(), job.getStartDate(), job.getEndDate(),
                        "REFRESHED".equals(job.getScopeType()));
            }
            throw new BusinessException("REPLAY_SELECTION_MISSING", "历史补推作业缺少原始筛选条件，无法恢复");
        }
        try {
            return objectMapper.readValue(job.getSelectionJson(), ReplaySelection.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("REPLAY_SELECTION_INVALID", "补推筛选条件无法解析");
        }
    }

    private ReplayCriteria criteria(String proCode, ReplaySelection selection, LaborProject project) {
        validateSelection(proCode, selection);
        String companyCode = normalized(selection.companyCode());
        String companyName = null;
        if (companyCode != null) {
            LaborCompany company = companyRepository.findByProCodeAndCollCropCode(proCode, companyCode)
                    .orElseThrow(() -> new BusinessException("COMPANY_NOT_FOUND", "参建企业不存在"));
            companyName = company.getCompanyName();
        }
        return new ReplayCriteria(proCode, project.getProjectName(), selection.taskType(), companyCode, companyName,
                selection.startDate(), selection.endDate());
    }

    private void validateSelection(String proCode, ReplaySelection selection) {
        if (selection == null) throw new BusinessException("REPLAY_SELECTION_REQUIRED", "请填写补推筛选条件");
        String requestedProject = normalized(selection.projectCode());
        if (requestedProject != null && !proCode.equals(requestedProject)) {
            throw new BusinessException("WORKSPACE_DATA_MISMATCH", "补推项目与当前项目工作区不一致");
        }
        if ((selection.startDate() == null) != (selection.endDate() == null)) {
            throw new BusinessException("REPLAY_DATE_RANGE_INVALID", "开始日期和结束日期必须同时填写");
        }
        if (selection.startDate() != null && selection.startDate().isAfter(selection.endDate())) {
            throw new BusinessException("REPLAY_DATE_RANGE_INVALID", "开始日期不能晚于结束日期");
        }
        if (selection.taskType() == PushTaskType.PROJECT && normalized(selection.companyCode()) != null) {
            throw new BusinessException("REPLAY_COMPANY_TYPE_INVALID", "项目类型不能按参建企业筛选");
        }
        if (!selection.projectWide() && selection.refreshSources()) {
            throw new BusinessException("REPLAY_REFRESH_SCOPE_INVALID", "重新生成任务只能用于按项目条件补推");
        }
    }

    private LocalDate sourceDate(PushTask task) {
        if (task.getBusinessDate() != null) return task.getBusinessDate();
        return LocalDate.ofInstant(task.getCreatedAt(), ZoneId.of("Asia/Shanghai"));
    }

    private boolean inDateRange(LocalDate value, LocalDate startDate, LocalDate endDate) {
        return startDate == null || (!value.isBefore(startDate) && !value.isAfter(endDate));
    }

    private String scopeDescription(ReplaySelection selection, ReplayCriteria criteria, int total) {
        if (!selection.projectWide()) return "选中任务 " + total + " 条";
        List<String> parts = new ArrayList<>();
        parts.add(criteria.projectName());
        parts.add(criteria.taskType() == null ? "全部类型" : taskTypeLabel(criteria.taskType()));
        if (criteria.companyName() != null) parts.add(criteria.companyName());
        if (criteria.startDate() != null) parts.add(criteria.startDate() + " 至 " + criteria.endDate());
        return String.join(" / ", parts);
    }

    private String taskTypeLabel(PushTaskType type) {
        return switch (type) {
            case PROJECT -> "项目";
            case COMPANY -> "参建企业";
            case TEAM -> "施工队";
            case PERSON -> "人员";
            case ATTENDANCE -> "考勤";
        };
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private PushReplayItemStatus itemStatus(PushTaskStatus status) {
        return switch (status) {
            case PENDING, WAITING_CONFIRM -> PushReplayItemStatus.PENDING;
            case RUNNING -> PushReplayItemStatus.RUNNING;
            case SUCCESS -> PushReplayItemStatus.SUCCESS;
            case FAILED -> PushReplayItemStatus.FAILED;
            case PAUSED, IGNORED -> PushReplayItemStatus.PAUSED;
        };
    }

    private String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessException("REASON_REQUIRED", "必须填写补推原因");
        return abbreviate(reason.trim(), 500);
    }

    private String jobNo() {
        return "RP" + JOB_TIME.format(Instant.now()) + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static ThreadFactory replayThreadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "push-replay-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private String abbreviate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record ReplaySelection(boolean projectWide, String projectCode, List<Long> taskIds,
                                  PushTaskType taskType, String companyCode,
                                  LocalDate startDate, LocalDate endDate, boolean refreshSources) {
        public ReplaySelection(boolean projectWide, String projectCode, List<Long> taskIds,
                               PushTaskType taskType, String companyCode,
                               LocalDate startDate, LocalDate endDate) {
            this(projectWide, projectCode, taskIds, taskType, companyCode, startDate, endDate, false);
        }
    }

    private record ReplayCriteria(String projectCode, String projectName, PushTaskType taskType,
                                  String companyCode, String companyName,
                                  LocalDate startDate, LocalDate endDate) {
    }

    public record ReplayPreview(String projectCode, String projectName, PushTaskType taskType,
                                String companyCode, String companyName, LocalDate startDate, LocalDate endDate,
                                int total, Map<PushTaskType, Integer> byType, int batchCount,
                                int attendanceCount, boolean refreshSources, String warning) {
    }

    public record ReplayJobView(Long id, String jobNo, String scopeType, String scopeDescription,
                                String projectCode, String projectName, PushTaskType taskType,
                                String companyCode, String companyName, LocalDate startDate, LocalDate endDate,
                                String reason, String requestedBy, PushReplayJobStatus status,
                                int totalCount, int successCount, int failedCount, int pendingCount,
                                int completedCount, int progressPercent, int totalBatchCount,
                                Map<PushTaskType, Integer> byType, String warning,
                                Instant startedAt, Instant completedAt, String lastError, Instant createdAt) {
    }

    public record ReplayItemView(Long id, Long sourceTaskId, Long replayTaskId, PushTaskType taskType,
                                 PushReplayItemStatus status, int attemptCount, int maxRetries,
                                 String lastErrorCode, String lastErrorMessage, Instant completedAt) {
        static ReplayItemView from(PushReplayItem item) {
            return new ReplayItemView(item.getId(), item.getSourceTaskId(), item.getReplayTaskId(),
                    item.getTaskType(), item.getStatus(), item.getAttemptCount(), item.getMaxRetries(),
                    item.getLastErrorCode(), item.getLastErrorMessage(), item.getCompletedAt());
        }
    }
}
